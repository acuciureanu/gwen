/*
 * Copyright 2014 Branko Juric, Brady Wood
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

import java.io.File

import scala.Option.option2Iterable
import scala.io.Source
import scala.language.postfixOps
import scala.util.{Failure => TryFailure}
import scala.util.{Success => TrySuccess}
import scala.util.Try

import com.typesafe.scalalogging.slf4j.LazyLogging

import gwen.ConsoleWriter
import gwen.Predefs.Kestrel
import gwen.dsl.Background
import gwen.dsl.EvalStatus
import gwen.dsl.Failed
import gwen.dsl.FeatureSpec
import gwen.dsl.Loaded
import gwen.dsl.Passed
import gwen.dsl.Scenario
import gwen.dsl.Skipped
import gwen.dsl.SpecNode
import gwen.dsl.SpecNormaliser
import gwen.dsl.SpecParser
import gwen.dsl.Step
import gwen.dsl.Tag
import gwen.dsl.prettyPrint

/**
 * Interprets incoming feature specs by parsing and evaluating
 * them.  All parsing is performed in the inherited [[gwen.dsl.SpecParser]].
 * All evaluation is dispatched to a mixed in [[gwen.eval.EvalEngine]].
 * 
 * @author Branko Juric
 */
class GwenInterpreter[T <: EnvContext] extends SpecParser with ConsoleWriter with SpecNormaliser with LazyLogging {
  engine: EvalEngine[T] =>

  /**
   * Initialises the interpreter by creating the environment context
   * 
   * @param options
   * 			command line options
   */
  private[eval] def initialise(options: GwenOptions): EnvContext = {
    logger.info("Initialising environment context")
    engine.init(options) tap { env =>
      logger.info(s"${env.getClass().getSimpleName()} initialised")
    }
  }
  
  /**
   * Closes the given environment context.
   * 
   * @param env
   * 			the environment context to close
   */
  private[eval] def close(env: EnvContext) {
    logger.info("Closing environment context")
    env.close();
  }
  
  /**
   * Resets the given environment context without closing it so it can be reused.
   * 
   * @param env
   * 			the environment context to reset
   */
  private[eval] def reset(env: EnvContext) {
    logger.info("Resetting environment context")
    env.reset();
  }
  
  /**
   * Interprets a single step and dispatches it for evaluation.
   *
   * @param input
   * 			the input step
   * @param env
   * 			the environment context
   * @return
   * 			the evaluated step (or an exception if a runtime error occurs)
   */
  private[eval] def interpretStep(input: String, env: EnvContext): Try[Step] = Try {
    (parseAll(step, input) match {
      case success @ Success(step, _) => 
        evaluateStep(step, env)
      case failure: NoSuccess => 
        sys.error(failure.toString)
    }) tap { result =>
      printNode(result)
    }
  }
  
  /**
   * Interprets an incoming feature.
   *
   * @param featureFile
   * 			the feature file
   * @param metaFiles
   * 			the meta files to load
   * @param tagFilters
   * 			user provided tag filters (includes:(tag, true) and excludes:(tag, false))
   * @param env
   * 			the environment context
   * @return
   *            the evaluated feature or nothing if the feature does not 
   *            satisfy specified tag filters
   */
  private[eval] def interpretFeature(featureFile: File, metaFiles: List[File], tagFilters: List[(Tag, Boolean)], env: EnvContext): Option[FeatureSpec] = 
    parseAll(spec, Source.fromFile(featureFile).mkString) match {
      case success @ Success(featureSpec, _) =>
        TagsFilter.filter(featureSpec, tagFilters) match {
          case Some(fspec) =>
            val metaSpecs = loadMeta(metaFiles, tagFilters, env)
            Some(evaluateFeature(normalise(fspec, Some(featureFile)), metaSpecs, env) tap { feature =>
              logger.info(s"Feature file interpreted: $featureFile")
              printNode(feature)
            })
          case None => 
            logger.info(s"Feature file skipped (does not satisfy tag filters): $featureFile")
            None
        }
      case failure: NoSuccess =>
        sys.error(failure.toString)
    }
  
  /**
   * Executes the given options.
   * 
   * @param options
   * 			the command line options
   * @param optEnv
   * 			optional environment context (None to have Gwen create an env context for each feature unit, 
   *    		Some(env) to reuse an environment context for all, default is None)
   * @param executor
   * 			implicit executor
   */
  def execute(options: GwenOptions, optEnv: Option[EnvContext] = None)(implicit executor: GwenExecutor[T] = new GwenExecutor(this)) = 
    executor.execute(options, optEnv)
  
  /**
   * Evaluates a given Gwen feature.
   * 
   * @param featureSpec
   * 			the Gwen feature to evaluate
   * @param metaSpecs
   * 			the loaded meta features
   * @param env
   * 			the environment context
   * @return
   * 			the evaluated Gwen feature
   */
  private def evaluateFeature(featureSpec: FeatureSpec, metaSpecs: List[FeatureSpec], env: EnvContext): FeatureSpec = logStatus {
    featureSpec.featureFile foreach { file =>
      logger.info(s"Interpreting feature file: $file")
    }
    logger.info(s"Evaluating feature: $featureSpec")
    FeatureSpec(
      featureSpec.feature, 
      None, 
      featureSpec.scenarios map { scenario =>
        if (scenario.isStepDef) {
          logger.info(s"Loading StepDef: ${scenario.name}")
          env.addStepDef(scenario) 
          Scenario(scenario.tags, scenario.name, scenario.steps map { step =>
            Step(step.keyword, step.expression, Loaded)
          })
        } else {
          evaluateScenario(scenario, env)
        }
      },
      featureSpec.featureFile, 
      metaSpecs)
  }
  
  /**
   * Evaluates a given scenario.
   * 
   * @param scenario
   * 			the scenario to evaluate
   * @param env
   * 			the environment context
   * @return
   * 			the evaluated scenario
   */
  private def evaluateScenario(scenario: Scenario, env: EnvContext): Scenario = logStatus {
    logger.info(s"Evaluating Scenario: $scenario")
    scenario.background map(evaluateBackground(_, env)) match {
      case None => 
        Scenario(scenario.tags, scenario.name, None, evaluateSteps(scenario.steps, env))
      case Some(background) => 
        Scenario(
          scenario.tags,
          scenario.name,
          Some(background),
          background.evalStatus match {
            case Passed(_) => evaluateSteps(scenario.steps, env)
            case _ => scenario.steps map { step =>
              Step(step.keyword, step.expression, Skipped)
            }
          })
    }
  }
  
  /**
   * Evaluates a given background.
   * 
   * @param background
   * 			the background to evaluate
   * @param env
   * 			the environment context
   * @return
   * 			the evaluated background
   */
  private def evaluateBackground(background: Background, env: EnvContext): Background = logStatus {
    logger.info(s"Evaluating Background: $background")
    Background(background.name, evaluateSteps(background.steps, env))
  }
  
  /**
   * Evaluates a list of steps.
   * 
   * @param steps 
   * 			the steps to evaluate
   * @param env
   * 			the environment context
   * @return
   * 		the list of evaluated steps
   */
  private def evaluateSteps(steps: List[Step], env: EnvContext): List[Step] = steps.foldLeft(List[Step]()) {
    (acc: List[Step], step: Step) => 
      (EvalStatus(acc.map(_.evalStatus)) match {
        case Failed(_, _) => Step(step.keyword, step.expression, Skipped)
        case _ => evaluateStep(step, env)
      }) :: acc
  } reverse
  
  /**
   * Evaluates a given step.
   * 
   * @param step
   * 			the step to evaluate
   * @param env
   * 			the environment context
   * @return
   * 			the evaluated step
   */
  private def evaluateStep(step: Step, env: EnvContext): Step = logStatus {
    logger.info(s"Evaluating Step: $step")
    env.getStepDef(step.expression) match {
      case None =>
        evaluate(step, env) { step =>  
          Try {
            engine.evaluate(step, env.asInstanceOf[T])
            step
          }
        }
      case (Some(stepDef)) =>
        logger.info(s"Evaluating StepDef: ${stepDef.name}")
        Step(step.keyword, step.expression, EvalStatus(evaluateSteps(stepDef.steps, env).map(_.evalStatus))) tap { step =>
          logger.info(s"StepDef evaluated: ${stepDef.name}")
        }
    }
  }
  
  /**
   * Evaluates a step and captures the result.
   * 
   * @param step 
   * 			the step to evaluate
   * @param env
   * 			the environment context
   * @param evalFunction
   * 		the step evaluation function
   */
  private def evaluate(step: Step, env: EnvContext)(evalFunction: (Step) => Try[Step]): Step = {
    val start = System.nanoTime
    evalFunction(step) match {
      case TrySuccess(step) => 
        Step(step.keyword, step.expression, Passed(System.nanoTime - start))
      case TryFailure(error) =>
        logger.error(error.getMessage())
        logger.debug(s"Exception: ", error)
        logger.error(env.toString)
        Step(step.keyword, step.expression, Failed(System.nanoTime - start, error))
    }
  }
  
  /**
   * Loads the meta.
   * 
   * @param metaFiles
   * 			the meta files to load
   * @param tagFilters
   * 			user provided tag filters (includes:(tag, true) and excludes:(tag, false))
   * @param env
   * 			the environment context
   */
  private[eval] def loadMeta(metaFiles: List[File], tagFilters: List[(Tag, Boolean)], env: EnvContext): List[FeatureSpec] =
    metaFiles flatMap { metaFile =>
      logger.info(s"Loading meta feature: $metaFile")
      interpretFeature(metaFile, Nil, tagFilters, env) tap { metaOpt =>
        metaOpt match {
          case Some(meta) =>
            meta.evalStatus match {
              case Passed(_) | Loaded =>
                logger.info(s"Loaded meta feature: $meta")
              case Failed(_, error) =>
                sys.error(s"Failed to load meta feature: $meta: ${error.getMessage()}")
              case _ =>
                sys.error(s"Failed to load meta feature: $meta")
            }
          case None => None
        }
      } 
    }
  
  private def printNode(node: SpecNode) {
    println
    println(prettyPrint(node))
    println
  } 
  
  /**
   * Logs the evaluation status of the given node.
   * 
   * @param node
   * 			the node to log the evaluation status of
   * @return
   * 			the input node 
   */
  private def logStatus[T <: SpecNode](node: T) = node tap { node =>
    node.evalStatus tap { status =>
      val statusMsg = s"$status ${node.getClass.getSimpleName}: ${node}"
      status match {
        case Passed(_) | Loaded => 
          logger.info(statusMsg)
        case Failed(_, _) => 
          logger.error(statusMsg)
        case _ => 
          logger.warn(statusMsg)
      }
    }
  }
  
}
