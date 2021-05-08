/*
 * Copyright 2021 Branko Juric, Brady Wood
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

package gwen.eval.engine

import gwen._
import gwen.model._
import gwen.model.gherkin.Examples
import gwen.model.gherkin.Scenario
import gwen.eval.EvalContext
import gwen.eval.EvalEngine
import gwen.eval.SpecNormaliser

import com.github.tototoshi.csv.CSVReader
import com.typesafe.scalalogging.LazyLogging

import java.io.File

/**
  * Examples evaluation engine.
  */
trait ExamplesEngine[T <: EvalContext] extends SpecNormaliser with LazyLogging {
    engine: EvalEngine[T] =>

  private [engine] def evaluateExamples(parent: Identifiable, examples: List[Examples], ctx: T): List[Examples] = {
    ctx.withEnv { env => 
      examples map { exs =>
        ctx.lifecycle.beforeExamples(parent, exs, env.scopes)
        exs.copy(
          withScenarios = exs.scenarios map { scenario =>
            evaluateScenario(exs, scenario, ctx)
          }
        ) tap { exs =>
          ctx.lifecycle.afterExamples(exs, env.scopes)
        }
      }
    }
  }

  /**
    * Loads the CSV examples for every Examples(file.csv) tag on the given outline and expands them.
    *
    * @param outline the scenario outline
    * @return a new scenario outline containing the loaded examples data
    *         or the unchanged outline if no csv data is specified or if incoming scenario is not an outline
    */
  private [eval] def expandCSVExamples(outline: Scenario, ctx: T): Scenario = {
    val csvExamples = outline.tags.flatMap { tag =>
      tag match {
        case Tag(_, name, Some(fileValue)) =>
          if (name == ReservedTags.Examples.toString) {
            val filepath = ctx.interpolate(fileValue)
            val examplesTag = tag.copy(withValue = Some(filepath))
            val file = new File(filepath)
            if (!file.exists()) Errors.missingOrInvalidImportFileError(examplesTag)
            if (!file.getName.toLowerCase.endsWith(".csv")) Errors.unsupportedDataFileError(examplesTag)
            val table = CSVReader.open(file).iterator.toList.zipWithIndex map { case (row, idx) => (idx + 1, row.toList) }
            Some(Examples(None, Nil, FeatureKeyword.nameOf(FeatureKeyword.Examples), s"Data file: $filepath", Nil, table, Nil))
          } else if (name.equalsIgnoreCase(ReservedTags.Examples.toString)) {
            Errors.invalidTagError(s"""Invalid Examples tag syntax: $tag - correct syntax is @Examples("path/file.csv")""")
          } else {
            None
          }
        case _ => None
      }
    }
    csvExamples match {
      case Nil => outline
      case _ =>
        val examples = normaliseScenarioOutline(
            outline.copy(withExamples = csvExamples),
            outline.background
          ).examples
        outline.copy(
          withExamples = outline.examples ++ examples
        )
    }
  }

}