/*
 * Copyright 2014-2020 Branko Juric, Brady Wood
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gwen.eval

import gwen._
import gwen.dsl._

import scala.util.Failure
import scala.util.Try
import scala.util.Success

import com.typesafe.scalalogging.LazyLogging

import java.{util => ju}

/**
  * Base trait for gwen evaluation engines. An evaluation engine performs the
  * actual processing work required to evaluate individual
  * [[gwen.dsl.Step Step]] expressions. This work can be done using various
  * available frameworks and technologies. This trait serves as a base for
  * concrete engine implementations coupled to specific such frameworks or
  * technologies.
  *
  * Evaluation engines are never invoked directly, but are rather invoked by
  * the [[GwenInterpreter]].  This interpreter can mix in any evaluation engine 
  * that has this trait.
  *
  * @author Branko Juric
  */
trait EvalEngine[T <: EnvContext] extends LazyLogging with EvalRules {

  // semaphores for managing synchronized StepDefs
  val stepDefSemaphors: ju.concurrent.ConcurrentMap[String, ju.concurrent.Semaphore] = 
    new ju.concurrent.ConcurrentHashMap()

  // dispatches lifecycle events to listeners
  val lifecycle = new LifecycleEventDispatcher()
  
  /**
    * Initialises the engine and returns a bootstrapped evaluation context.
    * This is a lifecycle method and as such it is called by the
    * [[GwenInterpreter]] at the appropriate times.
    * 
    * @param options command line options
    * @param scopes initial data scopes
    */
  private [eval] def init(options: GwenOptions): T

  /**
    * Should be overridden to evaluate a given step (this implementation 
    * can be used as a fallback as it simply throws an unsupported step 
    * exception)
    *
    * @param step the step to evaluate
    * @param env the environment context
    * @throws gwen.Errors.UndefinedStepException unconditionally thrown by
    *         this default implementation
    */
  def evaluate(step: Step, env: T): Unit = Errors.undefinedStepError(step)

  /**
    * Should be overridden to evaluate a priority step (this implementation returns None and can be overridden).
    * For example, a step that calls another step needs to execute with priority to ensure that there is no
    * match conflict between the two (which can occur if the step being called by a step is a StepDef or another step
    * that matches the entire calling step).
    */
  def evaluatePriority(parent: Identifiable, step: Step, env: T): Option[Step] = None

  /**
    * Evaluates a given scenario.
    */
  private[eval] def evaluateScenario(parent: Identifiable, scenario: Scenario, env: T): Scenario = {
    if (scenario.isStepDef || scenario.isDataTable) {
      if (!scenario.isStepDef) Errors.dataTableError(s"${ReservedTags.StepDef} tag also expected where ${ReservedTags.DataTable} is specified")
      lifecycle.beforeStepDef(parent, scenario, env.scopes)
      logger.info(s"Loading ${scenario.keyword}: ${scenario.name}")
      env.addStepDef(scenario)
      if (env.isParallel && scenario.isSynchronized) {
        stepDefSemaphors.putIfAbsent(scenario.name, new ju.concurrent.Semaphore(1))
      }
      val tSteps = lifecycle.transitionSteps(scenario, scenario.steps, Loaded, env.scopes)
      val steps = if (!scenario.isOutline) {
        tSteps
      } else {
        scenario.steps
      }
      val examples = if (scenario.isOutline) {
        scenario.examples map { exs =>
          exs.copy(
            withScenarios = exs.scenarios map { scn =>
              scn.copy(
                withBackground = scn.background map { bg =>
                  bg.copy(withSteps = bg.steps map { _.copy(withEvalStatus = Loaded) })
                },
                withSteps = scn.steps map { _.copy(withEvalStatus = Loaded) }
              )
            }
          )
        }
      } else  {
        scenario.examples
      }
      scenario.copy(
        withBackground = None,
        withSteps = steps,
        withExamples = examples
      ) tap { s => 
        lifecycle.afterStepDef(s, env.scopes)
      }
    } else {
      lifecycle.beforeScenario(parent, scenario, env.scopes)
      logger.info(s"Evaluating ${scenario.keyword}: $scenario")
      (if (!scenario.isOutline) {
        scenario.background map (bg => evaluateBackground(scenario, bg, env)) match {
          case None =>
            scenario.copy(
              withBackground = None,
              withSteps = evaluateSteps(scenario, scenario.steps, env)
            )
          case Some(background) =>
            val steps: List[Step] = background.evalStatus match {
              case Passed(_) => evaluateSteps(scenario, scenario.steps, env)
              case Skipped if background.steps.isEmpty => evaluateSteps(scenario, scenario.steps, env)
              case _ => scenario.steps map { _.copy(withEvalStatus = Skipped) }
            }
            scenario.copy(
              withBackground = Some(background),
              withSteps = steps
            )
        }
      } else {
        val isExpanded = scenario.isExpanded
        scenario.copy(
          withSteps = scenario.steps map { step =>
            if (isExpanded) {
              step.copy(withEvalStatus = Loaded)
            } else {
              lifecycle.transitionStep(scenario, step, Loaded, env.scopes)
            }
          },
          withExamples = evaluateExamples(scenario, scenario.examples, env)
        )
      }) tap { scenario =>
        lifecycle.afterScenario(scenario, env.scopes)
      }
    } tap { scenario =>
      logStatus(scenario)
    }
  }

  /**
    * Evaluates a given background.
    */
  private[eval] def evaluateBackground(parent: Identifiable, background: Background, env: T): Background = {
    lifecycle.beforeBackground(parent, background, env.scopes)
    logger.info(s"Evaluating ${background.keyword}: $background")
    background.copy(withSteps = evaluateSteps(background, background.steps, env)) tap { bg =>
      logStatus(bg)
      lifecycle.afterBackground(bg, env.scopes)
    }
  }
  
  /**
    * Evaluates a given step.
    */
  def evaluateStep(parent: Identifiable, step: Step, stepIndex: Int, env: T): Step = {
    val start = System.nanoTime - step.evalStatus.nanos
    val ipStep = doEvaluate(step.withCallerParams(parent), env) { env.interpolateParams }
    val isStep = doEvaluate(ipStep, env) { env.interpolate }
    val iStep = isStep.withNodePath(deriveNodePath(parent, step, isStep.name, env))
    var pStep: Option[Step] = None
    logger.info(s"Evaluating Step: $iStep")
    lifecycle.beforeStep(parent, iStep, env.scopes)
    val hStep = if (stepIndex == 0 && (parent.isInstanceOf[Scenario] && !parent.asInstanceOf[Scenario].isStepDef)) {
      Try(lifecycle.healthCheck(parent, iStep, env.scopes)) match {
        case Success(_) => iStep
        case Failure(e) => iStep.copy(withEvalStatus = Failed(System.nanoTime - start, e))
      }
    } else iStep
    val eStep = if (hStep != iStep) {
      hStep
    } else {
      doEvaluate(iStep, env) { s =>
        pStep = evaluatePriority(parent, s, env)
        pStep.getOrElse(s)
      }
      val hasSynthetic = pStep.flatMap(s => s.stepDef.map(_.isSynthetic)).getOrElse(false)
      pStep.filter(_.evalStatus.status != StatusKeyword.Failed || hasSynthetic).getOrElse {
        if (iStep.evalStatus.status != StatusKeyword.Failed) {
          Try(env.getStepDef(iStep.name)) match {
            case Failure(error) =>
              iStep.copy(withEvalStatus = Failed(System.nanoTime - start, new Errors.StepFailure(iStep, error)))
            case Success(stepDefOpt) =>
              (stepDefOpt match {
                case Some(stepDef) if env.stepScope.containsScope(stepDef.name) => None
                case stepdef => stepdef
              }) match {
                case None =>
                  doEvaluate(iStep, env) { step =>
                    step tap { _ =>
                      try {
                        evaluate(step, env)
                      } catch {
                        case e: Errors.UndefinedStepException =>
                          stepDefOpt.fold(throw e) { stepDef =>
                            Errors.recursiveStepDefError(stepDef, step)
                          }
                      }
                    }
                  }
                case (Some(stepDef)) =>
                  val sdPath = SourceRef.nodePath(s"${iStep.sourceRef.flatMap(_.nodePath).getOrElse("/")}/${stepDef.name}", 1)
                  val sdef = stepDef.withNodePath(sdPath)
                  if (stepDefSemaphors.containsKey(stepDef.name)) {
                    val semaphore = stepDefSemaphors.get(stepDef.name)
                    semaphore.acquire()
                    try {
                      logger.info(s"Synchronized StepDef execution started [StepDef: ${sdef.name}] [thread: ${Thread.currentThread().getName}]")
                      evalStepDef(iStep, sdef, iStep, env)
                    } finally {
                      logger.info(s"Synchronized StepDef execution finished [StepDef: ${sdef.name}] [thread: ${Thread.currentThread().getName}]")
                      semaphore.release()
                    }
                  } else {
                    evalStepDef(iStep, sdef, iStep, env)
                  }
              }
          }
        } else {
          iStep
        }
      }
    }
    val fStep = eStep.evalStatus match {
      case Failed(_, e: Errors.StepFailure) if e.getCause != null && e.getCause.isInstanceOf[Errors.UndefinedStepException] =>
        pStep.getOrElse(eStep)
      case _ =>
        eStep.evalStatus match {
          case Passed(_) => eStep
          case _ => pStep.filter(s => EvalStatus.isEvaluated(s.evalStatus.status)).getOrElse(eStep)
        }
    }
    env.finaliseStep(fStep) tap { step =>
      logStatus(step)
      lifecycle.afterStep(step, env.scopes)
    }
  }

  private def deriveNodePath(parent: Identifiable, step: Step, name: String, env: T): String = {
    val parentPath = env.topScope.getOpt("gwen.override.parent.nodePath") match {
      case Some(path) =>
        env.topScope.set("gwen.override.parent.nodePath", null)
        path
      case None =>
        parent match {
          case node: SpecNode => 
            node.sourceRef.flatMap(_.nodePath).getOrElse("/")
          case _ => ""
        }
    }
    env.topScope.getOpt("gwen.override.node.occurrence").map(_.toInt) match {
      case Some(occurence) =>
        env.topScope.set("gwen.override.node.occurrence", null)
        SourceRef.nodePath(s"$parentPath/$name", occurence)
      case None => 
        val occurrence = step.occurrenceIn(parent)
        SourceRef.nodePath(s"$parentPath/$name", if (occurrence > 0) occurrence else 1)
        
    }
  }
  
  /**
    * Evaluates a step and captures the result.
    * 
    * @param step the step to evaluate
    * @param env the environment context
    * @param evalFunction the step evaluation function
    */
  private[eval] def doEvaluate(step: Step, env: T)(evalFunction: (Step) => Step): Step = {
    val start = System.nanoTime - step.evalStatus.nanos
    Try(evalFunction(step)) match {
      case Success(evaluatedStep) =>
        val status = evaluatedStep.stepDef.map(_.evalStatus).getOrElse {
          evaluatedStep.evalStatus match {
            case Failed(_, error) => Failed(System.nanoTime - start, error)
            case _ => Passed(System.nanoTime - start)
          }
        }
        evaluatedStep.copy(withEvalStatus = status)
      case Failure(error) =>
        val failure = Failed(System.nanoTime - start, new Errors.StepFailure(step, error))
        step.copy(withEvalStatus = failure)
    }
  }

  def evalStepDef(parent: Identifiable, iStepDef: Scenario, step: Step, env: T): Step = {
    val stepDef = iStepDef.withCallerParams(step)
    val sdStep = step.copy(
      withStepDef = Some(stepDef)
    )
    logger.debug(s"Evaluating ${stepDef.keyword}: ${stepDef.name}")
    val eStep = doEvaluate(step, env) { s =>
      checkStepDefRules(sdStep, env)
      step
    }
    if (eStep.evalStatus.status == StatusKeyword.Failed) {
      eStep
    } else {
      env.stepScope.push(stepDef.name, iStepDef.params)
      try {
        val dataTableOpt = stepDef.tags.find(_.name.startsWith("DataTable(")) map { tag => DataTable(tag, step) }
        dataTableOpt foreach { table =>
          env.topScope.pushObject("table", table)
        }
        try {
          lifecycle.beforeStepDef(parent, stepDef, env.scopes)
          val steps = if (!stepDef.isOutline) {
            evaluateSteps(stepDef, stepDef.steps, env)
          } else {
            val isExpanded = stepDef.isExpanded
            stepDef.steps map { step =>
              if (isExpanded) {
                step.copy(withEvalStatus = Loaded)
              } else {
                lifecycle.transitionStep(stepDef, step, Loaded, env.scopes)
              }
            }
          }
          val examples = if (stepDef.isOutline) {
            evaluateExamples(stepDef, stepDef.examples, env)
          } else { 
            stepDef.examples
          }
          val eStepDef = stepDef.copy(
            withBackground = None,
            withSteps = steps,
            withExamples = examples)
          logger.debug(s"${stepDef.keyword} evaluated: ${stepDef.name}")
          lifecycle.afterStepDef(eStepDef, env.scopes) 
          step.copy(
            withStepDef = Some(eStepDef),
            withEvalStatus = eStepDef.evalStatus
          )
        } finally {
          dataTableOpt foreach { _ =>
            env.topScope.popObject("table")
          }
        }
      } finally {
        env.stepScope.pop
      }
    }
  }

  private def evaluateExamples(parent: Identifiable, examples: List[Examples], env: T): List[Examples] = { 
    examples map { exs =>
      lifecycle.beforeExamples(parent, exs, env.scopes)
      exs.copy(
        withScenarios = exs.scenarios.zipWithIndex map { case (scenario, index) =>
          evaluateScenario(exs, scenario, env)
        }
      ) tap { exs =>
        lifecycle.afterExamples(exs, env.scopes)
      }
    }
  }
  
  /**
    * Evaluates a list of steps.
    */
  def evaluateSteps(parent: Identifiable, steps: List[Step], env: T): List[Step] = {
    var behaviorCount = 0
    try {
      steps.zipWithIndex.foldLeft(List[Step]()) {
        (acc: List[Step], stepWithIndex: (Step, Int)) => 
          val (step, stepIndex) = stepWithIndex
          if (!StepKeyword.isAnd(step.keyword)) {
            env.addBehavior(BehaviorType.of(step.keyword))
            behaviorCount = behaviorCount + 1 
          }
          (EvalStatus(acc.map(_.evalStatus)) match {
            case status @ Failed(_, error) =>
              env.evaluate(evaluateStep(parent, step, stepIndex, env)) {
                val isAssertionError = status.isAssertionError
                val isHardAssert = env.evaluate(false) { AssertionMode.isHard }
                if (!isAssertionError || isHardAssert) {
                  lifecycle.transitionStep(parent, step, Skipped, env.scopes)
                } else {
                  evaluateStep(parent, step, stepIndex, env)
                }
              }
            case _ => evaluateStep(parent, step, stepIndex, env)
          }) :: acc
      } reverse
    } finally {
      0 until behaviorCount foreach { _ =>
        env.popBehavior()
      }
    }
  }

  /**
    * Repeats a step for each element in list of elements of type U.
    */
  def foreach[U](elements: ()=>Seq[U], name: String, parent: Identifiable, step: Step, doStep: String, env: T): Step = {
    val keyword = FeatureKeyword.nameOf(FeatureKeyword.Scenario)
    val elementItems = elements()
    val foreachSteps = elementItems.toList.zipWithIndex map { case (_, index) => 
      step.copy(
        withName = doStep.replaceAll(s"$ZeroChar", ""),
        withKeyword = if (index == 0) step.keyword else StepKeyword.nameOf(StepKeyword.And)
      )
    }
    val tags = List(Tag(ReservedTags.Synthetic), Tag(ReservedTags.ForEach), Tag(ReservedTags.StepDef))
    val forEachPath = SourceRef.nodePath(s"${step.sourceRef.flatMap(_.nodePath).getOrElse("/")}/$name", 1)
    val forEachSourceRef = step.sourceRef.map(_.withNodePath(forEachPath))
    val preForeachStepDef = Scenario(forEachSourceRef, tags, keyword, name, Nil, None, foreachSteps, Nil, Nil, step.cumulativeParams)
    lifecycle.beforeStepDef(step, preForeachStepDef, env.scopes)
    val steps =
      elementItems match {
        case Nil =>
          logger.info(s"For-each[$name]: none found")
          Nil
        case elems =>
          val noOfElems = elems.size
          logger.info(s"For-each[$name]: $noOfElems found")
          try {
            if(Try(env.getBoundReferenceValue(name)).isSuccess) {
              Errors.ambiguousCaseError(s"For-each element name '$name' already bound (use a free name instead)")
            }
            elems.zipWithIndex.foldLeft(List[Step]()) { case (acc, (elem, index)) =>
              val elemNo = index + 1
              val params = elem match {
                case stringValue: String =>
                  env.topScope.set(name, stringValue)
                  if (env.isDryRun) {
                    env.topScope.pushObject(name, elem)
                  }
                  List((name, stringValue))
                case data: ScopedData =>
                  env.topScope.pushObject(name, elem)
                  data.findEntries(_ => true).toList
                case _ =>
                  env.topScope.pushObject(name, elem)
                  List((name, s"$name $elemNo"))
              }
              env.topScope.set(s"$name index", index.toString)
              env.topScope.set(s"$name number", elemNo.toString)
              env.topScope.set("gwen.override.parent.nodePath", forEachPath)
              env.topScope.set("gwen.override.node.occurrence", elemNo.toString)
              (try {
                EvalStatus(acc.map(_.evalStatus)) match {
                  case status @ Failed(_, error)  =>
                    val isAssertionError = status.isAssertionError
                    val isSoftAssert = env.evaluate(false) { isAssertionError && AssertionMode.isSoft }
                    val failfast = env.evaluate(false) { GwenSettings.`gwen.feature.failfast` }
                    if (failfast && !isSoftAssert) {
                      logger.info(s"Skipping [$name] $elemNo of $noOfElems")
                      lifecycle.transitionStep(preForeachStepDef, foreachSteps(index).copy(withParams = params), Skipped, env.scopes)
                    } else {
                      logger.info(s"Processing [$name] $elemNo of $noOfElems")
                      evaluateStep(preForeachStepDef, Step(step.sourceRef, if (index == 0) step.keyword else StepKeyword.nameOf(StepKeyword.And), doStep, Nil, None, Nil, None, Pending, params, Nil), index, env)
                    }
                  case _ =>
                    logger.info(s"Processing [$name] $elemNo of $noOfElems")
                    evaluateStep(preForeachStepDef, Step(step.sourceRef, if (index == 0) step.keyword else StepKeyword.nameOf(StepKeyword.And), doStep, Nil, None, Nil, None, Pending, params, Nil), index, env)
                }
              } finally {
                env.topScope.popObject(name)
              }) :: acc
            } reverse
          } finally {
            env.topScope.set("gwen.override.node.occurrence", null)
            env.topScope.set("gwen.override.parent.nodePath", null)
            env.topScope.set(name, null)
            env.topScope.set(s"$name index", null)
            env.topScope.set(s"$name number", null)
          }
      }
    val foreachStepDef = preForeachStepDef.copy(withSteps = steps)
    lifecycle.afterStepDef(foreachStepDef, env.scopes)
    step.copy(withStepDef = Some(foreachStepDef))
  }
  
  /**
    * Logs the evaluation status of the given spec.
    * 
    * @param spec the spec to log
    * @return the logged status message
    */
  private[eval] def logStatus(spec: FeatureSpec): Unit = {
    logStatus(spec.nodeType, spec.name, spec.evalStatus)
  }

  /**
    * Logs the evaluation status of the given node.
    * 
    * @param node the node to log
    * @return the logged status message
    */
  private[eval] def logStatus(node: SpecNode): Unit = {
      logStatus(node.nodeType, node.name, node.evalStatus)
  }
  
  private def logStatus(nodeType: NodeType.Value, name: String, evalStatus: EvalStatus): Unit = { 
    val msg = s"$evalStatus $nodeType: $name"
    evalStatus match {
      case Loaded => logger.debug(msg)
      case Passed(_) => logger.info(msg)
      case Failed(_, _) => logger.error(msg)
      case Sustained(_, _) => logger.warn(msg)
      case _ => logger.warn(msg)
    }
  }
  
}
