/*
 * Copyright 2014-2021 Branko Juric, Brady Wood
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
package gwen.core.report.html

import gwen.core._
import gwen.core.Formatting._
import gwen.core.GwenOptions
import gwen.core.model._
import gwen.core.model.gherkin._
import gwen.core.report.ReportFormat
import gwen.core.report.ReportFormatter

import HtmlReportFormatter._

import scala.io.Source
import scala.util.Try
import scalatags.Text.all._
import scalatags.Text.TypedTag

import java.io.File
import java.text.DecimalFormat
import scala.concurrent.duration.Duration
import java.util.Date

/** Formats the feature summary and detail reports in HTML. */
trait HtmlReportFormatter extends ReportFormatter {

  private val percentFormatter = new DecimalFormat("#.##")

  /**
    * Formats the feature detail report as HTML.
    *
    * @param options     gwen command line options
    * @param info        the gwen implementation info
    * @param unit        the feature input
    * @param result      the feature result to report
    * @param breadcrumbs names and references for linking back to parent reports
    * @param reportFiles the target report files (head = detail, tail = metas)
    */
  override def formatDetail(options: GwenOptions, info: GwenInfo, unit: FeatureUnit, result: SpecResult, breadcrumbs: List[(String, File)], reportFiles: List[File]): Option[String] = {

    val reportDir = HtmlReportConfig.reportDir(options).get
    val metaResults = result.metaResults
    val featureName = result.spec.specFile.map(_.getPath()).getOrElse(result.spec.feature.name)
    val title = s"${result.spec.specType} Detail"
    val summary = result.summary
    val screenshots = result.screenshots
    val rootPath = relativePath(reportFiles.head, reportDir).filter(_ == File.separatorChar).flatMap(_ => "../")
    val language = result.spec.feature.language

    Some(
      s"""<!DOCTYPE html>
<html lang="en">
  ${formatHtmlHead(s"$title - $featureName", rootPath).render}
  <body>
    ${formatReportHeader(info, title, featureName, rootPath).render}
    ${formatDetailStatusHeader(unit, result, rootPath, breadcrumbs, screenshots, true).render}
    <div class="panel panel-default">
      <div class="panel-heading" style="padding-right: 20px; padding-bottom: 0px; border-style: none;">${
        if (result.spec.feature.tags.nonEmpty)
          s"""
        <span class="grayed"><p><small>${result.spec.feature.tags.map(t => escapeHtml(t.toString)).mkString("<br>")}</small></p></span>""" else ""
      }${ if (language != "en") s"""
        <span class="grayed"><p><small># language: $language</small></p></span>
        """ else ""}
        <span class="label label-black">${result.spec.specType}</span>
        ${escapeHtml(result.spec.feature.name)}${formatDescriptionLines(result.spec.feature.description, None).render}
        <div class="panel-body" style="padding-left: 0px; padding-right: 0px; margin-right: -10px;">
          <span class="pull-right grayed" style="padding-right: 10px;"><small>Overhead: ${formatDuration(result.overhead)}</small></span>
          <table width="100%" cellpadding="5">
            ${formatProgressBar(NodeType.Rule, summary.ruleCounts).render}
            ${formatProgressBar(NodeType.Scenario, summary.scenarioCounts).render}
            ${formatProgressBar(NodeType.Step, summary.stepCounts).render}
          </table>
        </div>
      </div>
    </div>${
        if (metaResults.nonEmpty) {
          val count = metaResults.size
          val metaStatus = EvalStatus(metaResults.map(_.evalStatus))
          val status = metaStatus.status
          s"""
    <div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
      <ul class="list-group">
        <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px; margin-right: 10px;">
          <span class="label label-${cssStatus(status)}">Meta</span>
          <a class="text-${cssStatus(status)}" role="button" data-toggle="collapse" href="#meta" aria-expanded="true" aria-controls="meta">
            $count meta feature${if (count > 1) "s" else ""}
          </a>
          <span class="pull-right"><small>${formatDuration(DurationOps.sum(metaResults.map(_.elapsedTime)))}</small></span>
        </li>
      </ul>  
      <div id="meta" class="panel-collapse collapse">
        <div class="panel-body">
          <ul class="list-group">
            <li class="list-group-item list-group-item-${cssStatus(status)}">
              <div class="container-fluid" style="padding: 0px 0px">
                ${(metaResults.zipWithIndex map { case (res, rowIndex) => formatSummaryLine(res, if (GwenSettings.`gwen.report.suppress.meta`) None else Some(s"meta/${reportFiles.tail(rowIndex).getName}"), None, rowIndex) }).mkString}
              </div>
            </li>
          </ul>
        </div>
      </div>
    </div>"""
        } else ""
      }${(result.spec.scenarios.zipWithIndex map { case (s, idx) => formatScenario(s, s"$idx") }).mkString}
       ${(result.spec.rules.zipWithIndex map { case (s, idx) => formatRule(s, s"$idx") }).mkString}
  </body>
</html>
""")
  }

  private def formatDescriptionLines(description: List[String], status: Option[StatusKeyword.Value]): Option[Seq[TypedTag[String]]] = {
    val bgClass = status.map(cssStatus).getOrElse("default")
    if (description.nonEmpty) {
      Some(
        Seq(
          p,
          ul(`class` := s"list-group bg-$bgClass",
            for (line <- description)
            yield
            li(`class` := s"list-group-item bg-$bgClass",
              line
            )
          )
        )
      )
    } else {
      None
    }
  }

  private def formatScenario(scenario: Scenario, scenarioId: String): String = {
    val status = scenario.evalStatus.status
    val conflict = scenario.steps.map(_.evalStatus.status).exists(_ != status)
    val tags = scenario.tags
    val scenarioKeywordPixels = noOfKeywordPixels(scenario.steps)
    s"""
    <a name="scenario-$scenarioId"></a><div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
      <ul class="list-group">
        <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px; margin-right: 10px;">${  
      if (scenario.isStepDef)
        s"""
          <span class="grayed"><p><small>${scenario.sourceRef.map(ref => escapeHtml(ref.toString)).mkString("<br>")}</small></p></span>""" else ""
    }${  
      if (tags.nonEmpty)
        s"""
          <span class="grayed"><p><small>${tags.map(t => escapeHtml(t.toString)).mkString("<br>")}</small></p></span>""" else ""
    }
          <span class="label label-${cssStatus(status)}">${if (scenario.isForEach) "ForEach" else scenario.keyword}</span>${
      if ((scenario.steps.size + scenario.background.map(_.steps.size).getOrElse(0)) > 1 && !scenario.isForEach)
        s"""
          <span class="pull-right"><small>${durationOrStatus(scenario.evalStatus)}</small></span>""" else ""
    }
          ${escapeHtml(scenario.name)}${if (!scenario.isForEach) s"${formatDescriptionLines(scenario.description, Some(status)).render}" else { if(scenario.steps.isEmpty) """ <span class="grayed"><small>-- none found --</small></span>""" else ""}}
        </li>
      </ul>
      <div class="panel-body">${
      (scenario.background map { background =>
        val status = background.evalStatus.status
        val backgroundId = s"$scenarioId-background"
        val keywordPixels = noOfKeywordPixels(background.steps)
        s"""
        <div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
          <ul class="list-group">
            <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px;">
              <span class="label label-${cssStatus(status)}">${background.keyword}</span>
              <span class="pull-right"><small>${durationOrStatus(background.evalStatus)}</span></small>
              ${escapeHtml(background.name)}${formatDescriptionLines(background.description, Some(status)).render}
            </li>
          </ul>
          <div class="panel-body">
            <ul class="list-group" style="margin-right: -10px; margin-left: -10px">${
          (background.steps.zipWithIndex map { case (step, index) =>
            formatStepLine(step, step.evalStatus.status, s"$backgroundId-${step.uuid}-${index + 1}", keywordPixels)
          }).mkString
        }
            </ul>
          </div>
        </div>"""
      }).getOrElse("")
    }
        <div class="panel-${cssStatus(status)} ${if (conflict) s"bg-${cssStatus(status)}" else ""}" style="margin-bottom: 0px; ${if (conflict) "" else "border-style: none;"}">
          <ul class="list-group">${
          (scenario.steps.zipWithIndex flatMap { case (step, index) =>
            if (!scenario.isOutline) {
              Some(formatStepLine(step, step.evalStatus.status, s"$scenarioId-${step.uuid}-${index + 1}", scenarioKeywordPixels))
            } else if (!scenario.isExpanded) {
              Some(formatRawStepLine(step, scenario.evalStatus.status, scenarioKeywordPixels))
            } else None
          }).mkString}
          </ul>
        ${if (scenario.isOutline) formatExamples(scenario.examples, scenarioId, scenarioKeywordPixels) else ""}
        </div>
      </div>
    </div>"""
  }

  private def formatExamples(examples: List[Examples], outlineId: String, keywordPixels: Int): String = (examples.zipWithIndex map { case (exs, index) =>
    val exampleId = s"$outlineId-examples-${index}"
    val status = exs.evalStatus.status
    s"""
       <p></p>
       <div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
         <ul class="list-group">
           <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px; margin-right: 10px;">
             <span class="label label-${cssStatus(status)}">${exs.keyword}</span>
             <span class="pull-right"><small>${durationOrStatus(exs.evalStatus)}</small></span>
             ${escapeHtml(exs.name)}${formatDescriptionLines(exs.description, Some(status)).render}
           </li>
         </ul>
        <div class="panel-body">
          <ul class="list-group" style="margin-right: -10px; margin-left: -10px">${
            formatExampleHeader(exs.evalStatus, exs.table, keywordPixels)}${
            (exs.scenarios.zipWithIndex map { case (scenario, subindex) =>
              formatExampleRow(scenario, exs.table, subindex + 1, s"$exampleId-${subindex}", keywordPixels)
            }).mkString
          }
          </ul>
        </div>
      </div>"""
  }).mkString

  private def formatExampleHeader(evalStatus: EvalStatus, table: List[(Int, List[String])], keywordPixels: Int): String = {
              val status = evalStatus.status
              val line = table.head._1
              s"""
                <li class="list-group-item list-group-item-${cssStatus(status)} ${if (EvalStatus.isError(status)) s"bg-${cssStatus(status)}" else ""}">
                <div class="bg-${cssStatus(status)}">
                  <div class="line-no"><small>${if (line > 0) line else ""}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"> </div>${formatDataRow(table, 0, status)}
                </div>
              </li>"""
  }
  private def formatExampleRow(scenario: Scenario, table: List[(Int, List[String])], rowIndex: Int, exampleId: String, keywordPixels: Int): String = {
              val line = table(rowIndex)._1
              val status = scenario.evalStatus.status
              val rowHtml = formatDataRow(table, rowIndex, status)
              s"""
                <li class="list-group-item list-group-item-${cssStatus(status)} ${if (EvalStatus.isError(status)) s"bg-${cssStatus(status)}" else ""}">
                <div class="bg-${cssStatus(status)}">
                  <span class="pull-right"><small>${durationOrStatus(scenario.evalStatus)}</small></span>
                  <div class="line-no"><small>${if (line > 0) line else ""}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"> </div>${if (status != StatusKeyword.Failed) formatExampleLink(rowHtml, status, s"$exampleId") else rowHtml }
                  ${formatAttachments(scenario.attachments, status)} ${formatExampleDiv(scenario, status, exampleId)}
                </div>
              </li>"""
  }

  private def formatExampleLink(rowHtml: String, status: StatusKeyword.Value, exampleId: String): String =
                  s"""<a class="inverted inverted-${cssStatus(status)}" role="button" data-toggle="collapse" href="#$exampleId" aria-expanded="true" aria-controls="$exampleId">${rowHtml}</a>"""

  private def formatExampleDiv(scenario: Scenario, status: StatusKeyword.Value, exampleId: String): String = s"""
                  <div id="$exampleId" class="panel-collapse collapse${if (status == StatusKeyword.Failed) " in" else ""}" role="tabpanel">
                  ${formatScenario(scenario, exampleId)}
                  </div>"""

  private def formatStepDataTable(step: Step, keywordPixels: Int): String = {
      val status = step.evalStatus.status
              s"""
              ${step.table.indices map { rowIndex =>
                val line = step.table(rowIndex)._1
              s"""
                <div class="bg-${cssStatus(status)}">
                  <div class="line-no"><small>${if (line > 0) line else ""}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"> </div>${formatDataRow(step.table, rowIndex, status)}
                </div>"""} mkString}"""
  }

  private def formatStepDocString(step: Step, keywordPixels: Int): String = {
      val status = step.evalStatus.status
      val docString = step.docString.get
      val contentType = docString._3
      s"""
              ${formatDocString(docString, false).split("""\r?\n""").zipWithIndex  map { case (contentLine, index) =>
                val line = docString._1 + index
              s"""
                <div class="bg-${cssStatus(status)}">
                  <div class="line-no"><small>${if (line > 0) line else ""}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"> </div><code class="bg-${cssStatus(status)} doc-string">${escapeHtml(contentLine)}</code>${if (index == 0) contentType.map(cType => s"""<code class="bg-${cssStatus(status)} doc-string-type">${escapeHtml(cType)}</code>""").getOrElse("") else ""}
                </div>"""} mkString}"""
  }

  private def formatDataRow(table: List[(Int, List[String])], rowIndex: Int, status: StatusKeyword.Value): String = {
    s"""<code class="bg-${cssStatus(status)} data-table">${escapeHtml(Formatting.formatTableRow(table, rowIndex))}</code>"""
  }

  private def formatRule(rule: Rule, ruleId: String): String = {
    val status = rule.evalStatus.status
    val conflict = rule.scenarios.map(_.evalStatus.status).exists(_ != status)
    s"""
    <a name="rule-$ruleId"></a><div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
      <ul class="list-group">
        <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px; margin-right: 10px;">
          <span class="label label-${cssStatus(status)}">${rule.keyword}</span>${
      if (rule.evalScenarios.size > 1)
        s"""
          <span class="pull-right"><small>${durationOrStatus(rule.evalStatus)}</small></span>""" else ""
      }
          ${escapeHtml(rule.name)}${formatDescriptionLines(rule.description, Some(status)).render}
        </li>
      </ul>
      <div class="panel-body">${
      (rule.background map { background =>
        val status = background.evalStatus.status
        val backgroundId = s"$ruleId-background"
        s"""
        <div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
          <ul class="list-group">
            <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px;">
              <span class="label label-${cssStatus(status)}">${background.keyword}</span>
              <span class="pull-right"><small>${durationOrStatus(background.evalStatus)}</span></small>
              ${escapeHtml(background.name)}${formatDescriptionLines(background.description, Some(status)).render}
            </li>
          </ul>
          <div class="panel-body">
            <ul class="list-group" style="margin-right: -10px; margin-left: -10px">${
          val keywordPixels = noOfKeywordPixels(background.steps)
          (background.steps.zipWithIndex map { case (step, index) =>
            formatStepLine(step, step.evalStatus.status, s"$backgroundId-${step.uuid}-${index + 1}", keywordPixels)
          }).mkString
        }
            </ul>
          </div>
        </div>"""
      }).getOrElse("")
    }
        <div class="panel-${cssStatus(status)} ${if (conflict) s"bg-${cssStatus(status)}" else ""}" style="margin-bottom: 0px; ${if (conflict) "" else "border-style: none;"}">
          <ul class="list-group">${
          (rule.scenarios.zipWithIndex map { case (scenario, index) =>
            formatScenario(scenario, s"$ruleId-scenario-${scenario.uuid}-${index + 1}")
          }).mkString}
          </ul>
        </div>
      </div>
    </div>"""
  }
  
  /**
    * Formats the feature summary report as HTML.
    * 
    * @param options gwen command line options
    * @param info the gwen implementation info
    * @param summary the accumulated feature results summary
    */
  override def formatSummary(options: GwenOptions, info: GwenInfo, summary: ResultsSummary): Option[String] = {
    
    val reportDir = HtmlReportConfig.reportDir(options).get
    val title = "Feature Summary"
  
    Some(s"""<!DOCTYPE html>
<html lang="en">
  ${formatHtmlHead(title, "").render}
  <body>
    ${formatReportHeader(info, title, if (options.args.isDefined) escapeHtml(options.commandString(info)).render else "", "")}
    ${formatSummaryStatusHeader(summary).render}
    <div class="panel panel-default">
      <div class="panel-heading" style="padding-right: 20px; padding-bottom: 0px; border-style: none;">
        <span class="label label-black">Results</span>
        <div class="panel-body" style="padding-left: 0px; padding-right: 0px; margin-right: -10px;">
          <span class="pull-right grayed" style="padding-right: 10px;"><small>Overhead: ${formatDuration(summary.overhead)}</small></span>
          <table width="100%" cellpadding="5">
            ${formatProgressBar(NodeType.Feature, summary.featureCounts).render}
            ${formatProgressBar(NodeType.Rule, summary.ruleCounts).render}
            ${formatProgressBar(NodeType.Scenario, summary.scenarioCounts).render}
            ${formatProgressBar(NodeType.Step, summary.stepCounts).render}
          </table>
        </div>
      </div>
    </div>${(StatusKeyword.reportables.reverse map { status => 
    summary.results.zipWithIndex.filter { _._1.evalStatus.status == status } match {
      case Nil => ""
      case results => s"""
    <div class="panel panel-${cssStatus(status)} bg-${cssStatus(status)}">
      <ul class="list-group">
        <li class="list-group-item list-group-item-${cssStatus(status)}" style="padding: 10px 10px; margin-right: 10px;">
          <span class="label label-${cssStatus(status)}">$status</span>${
          val count = results.size
          val total = summary.results.size
          val countOfTotal = s"""$count ${if (count != total) s" of $total features" else s"feature${if (total > 1) "s" else ""}"}"""
          s"""$countOfTotal${if (count > 1) s"""
          <span class="pull-right"><small>${formatDuration(DurationOps.sum(results.map(_._1.elapsedTime)))}</small></span>""" else ""}"""}
        </li>
      </ul>
      <div class="panel-body">
        <ul class="list-group">
          <li class="list-group-item list-group-item-${cssStatus(status)}">
            <div class="container-fluid" style="padding: 0px 0px">${
                (results.zipWithIndex map { case ((result, resultIndex), rowIndex) => 
                  val reportFile = result.reports.get(ReportFormat.html).head
                  formatSummaryLine(result, Some(s"${relativePath(reportFile, reportDir).replace(File.separatorChar, '/')}"), Some(resultIndex + 1), rowIndex)
                }).mkString}
            </div>
          </li>
        </ul>
      </div>
    </div>"""}}).mkString}
  </body>
</html>
    """)
  }

  private def formatProgressBar(nodeType: NodeType.Value, counts: Map[StatusKeyword.Value, Int]): Option[TypedTag[String]] = { 
    for (total <- Some(counts.values.sum).filter(_ > 0))
    yield
    tr(
      td(attr("align") := "right",
        span(style := "white-space: nowrap;",
          s"$total $nodeType${if (total > 1) "s" else ""}"
        )
      ),
      td(width := "99%",
        div(`class` := "progress",
          for { 
            status <- StatusKeyword.reportables
            count = counts.getOrElse(status, 0)
            percentage = calcPercentage(count, total)
          } yield
          div(`class` := s"progress-bar progress-bar-${cssStatus(status)}", style := s"width: $percentage%;",
            span(
              s"$count $status - ${percentageRounded(percentage)}%"
            )
          )
        )
      )
    )
  }
  
  private def formatSummaryLine(result: SpecResult, reportPath: Option[String], sequenceNo: Option[Int], rowIndex: Int): String = {
    val featureName = Option(result.spec.feature.name).map(_.trim).filter(!_.isEmpty).getOrElse(result.spec.specFile.map(_.getName()).map(n => Try(n.substring(0, n.lastIndexOf('.'))).getOrElse(n)).getOrElse("-- details --"))
    val reportingStatus = result.evalStatus match {
      case Passed(nanos) if result.sustainedCount > 0 => Sustained(nanos, null)
      case status => status
    }
    s"""
                <div class="row${if (rowIndex % 2 == 1) s" bg-altrow-${cssStatus(result.evalStatus.status)}" else "" }">
                  <div class="col-md-3" style="padding-left: 0px">${sequenceNo.map(seq => s"""
                    <div class="line-no"><small>$seq</small></div>""").getOrElse("")}
                    <span style="padding-left: 15px; white-space: nowrap;"><small>${escapeHtml(result.finished.toString)}</small></span>
                  </div>
                  <div class="col-md-4">${reportPath.fold(s"${escapeHtml(featureName)}") { rpath =>
                    s"""<a class="text-${cssStatus(reportingStatus.status)}" style="color: ${linkColor(reportingStatus.status)};" href="$rpath"><span class="text-${cssStatus(reportingStatus.status)}">${escapeHtml(featureName)}</span></a>"""}}
                  </div>
                  <div class="col-md-5">
                    <span class="pull-right"><small>${formatDuration(result.elapsedTime)}</small></span> ${result.spec.specFile.map(_.getPath()).getOrElse("")}
                  </div>
                </div>"""
  }
  private def formatStepLine(step: Step, status: StatusKeyword.Value, stepId: String, keywordPixels: Int): String = {
    val stepDef = step.stepDef
    s"""<li class="list-group-item list-group-item-${cssStatus(status)} ${if (EvalStatus.isError(status) || EvalStatus.isDisabled(status)) s"bg-${cssStatus(status)}" else ""}">
                <div class="bg-${cssStatus(status)} ${if (EvalStatus.isDisabled(status)) "text-muted" else ""}">
                  <span class="pull-right"><small>${durationOrStatus(step.evalStatus)}</small></span>
                  <div class="line-no"><small>${step.sourceRef.map(_.pos.line).getOrElse("")}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"><strong>${step.keyword}</strong></div> ${if (stepDef.nonEmpty && status == StatusKeyword.Passed) formatStepDefLink(step, status, s"$stepId-stepDef") else s"${escapeHtml(step.name)}"}
                  ${formatAttachments(step.deepAttachments, status)} ${stepDef.map{ case (stepDef, _) => if (EvalStatus.isEvaluated(status)) { formatStepDefDiv(stepDef, status, s"$stepId-stepDef") } else ""}.getOrElse("")}${if (step.docString.nonEmpty) formatStepDocString(step, keywordPixels) else if (step.table.nonEmpty) formatStepDataTable(step, keywordPixels) else ""}
                </div>
                ${if (EvalStatus.isError(status) && stepDef.isEmpty) s"""
                <ul>
                  <li class="list-group-item list-group-item-${cssStatus(status)} ${if (EvalStatus.isError(status)) s"bg-${cssStatus(status)}" else ""}">
                    <div class="bg-${cssStatus(status)}">
                      <span class="badge badge-${cssStatus(status)}${if(status != StatusKeyword.Passed && status != StatusKeyword.Loaded) s""" badge-${status.toString.toLowerCase}-issue""" else ""}">$status</span> <span class="text-${cssStatus(status)}"><small>${escapeHtml(step.evalStatus.timestamp.toString)} - ${escapeHtml(step.evalStatus.message)}</small></span>
                    </div>
                  </li>
                </ul>""" else ""}
              </li>"""
    }

  private def formatRawStepLine(step: Step, status: StatusKeyword.Value, keywordPixels: Int): String = s"""
              <li class="list-group-item list-group-item-${cssStatus(status)} ${if (EvalStatus.isError(status)) s"bg-${cssStatus(status)}" else ""}">
                <div class="bg-${cssStatus(status)}">
                  <div class="line-no"><small>${step.sourceRef.map(_.pos.line).getOrElse("")}</small></div>
                  <div class="keyword-right" style="width:${keywordPixels}px"><strong>${step.keyword}</strong></div> ${escapeHtml(step.name)}
                </div>
              </li>"""
  
  private def formatStepDefLink(step: Step, status: StatusKeyword.Value, stepDefId: String): String = 
    s"""<a class="inverted inverted-${cssStatus(step.evalStatus.status)}" role="button" data-toggle="collapse" href="#$stepDefId" aria-expanded="true" aria-controls="$stepDefId">${escapeHtml(step.name)}</a>"""
                  
  private def formatStepDefDiv(stepDef: Scenario, status: StatusKeyword.Value, stepDefId: String): String = s"""
                  <div id="$stepDefId" class="panel-collapse collapse${if (status != StatusKeyword.Passed) " in" else ""}" role="tabpanel">
                  ${formatScenario(stepDef, stepDefId)}
                  </div>"""
    
  private def formatAttachments(attachments: List[(String, File)], status: StatusKeyword.Value) = s"""
                  &nbsp; ${if (attachments.size > 1) s"""
                  <div class="dropdown bg-${cssStatus(status)}">
                    <button class="btn btn-${cssStatus(status)} dropdown-toggle" type="button" id="dropdownMenu1" data-toggle="dropdown" style="vertical-align: text-top">
                      <strong>attachments</strong>
                      <span class="caret"></span>
                    </button>
                    <ul class="dropdown-menu pull-right" role="menu" style="padding-left:0;">${(attachments.zipWithIndex map { case ((name, file), index) =>
                    s"""
                      <li role="presentation" class="text-${cssStatus(status)}"><a role="menuitem" tabindex="-1" href="${attachmentHref(file)}" target="_blank"><span class="line-no" style="width: 0px;">${index + 1}. &nbsp; </span>${escapeHtml(name)}<span class="line-no" style="width: 0px;"> &nbsp; </span></a></li>"""}).mkString }
                    </ul>
                  </div>""" else if (attachments.size == 1) {
                    val (name, file) = attachments(0)
                    s"""
                    <a href="${attachmentHref(file)}" target="_blank" style="color: ${linkColor(status)};">
                      <strong style="font-size: 12px;">$name</strong>
                    </a>"""} else ""}"""

  private def attachmentHref(file: File) = if (FileIO.hasFileExtension("url", file)) Source.fromFile(file).mkString.trim else s"attachments/${file.getName}"
      
  private def percentageRounded(percentage: Double): String = percentFormatter.format(percentage)
  private def calcPercentage(count: Int, total: Int): Double = 100 * count.toDouble / total.toDouble
  private def durationOrStatus(evalStatus: EvalStatus) =
    if (EvalStatus.isEvaluated(evalStatus.status) && !EvalStatus.isDisabled(evalStatus.status))  {
      formatDuration(evalStatus.duration)
    } else {
      evalStatus.status
    }
  
}

object HtmlReportFormatter {
  
  private val cssStatus = Map(
    StatusKeyword.Passed -> "success", 
    StatusKeyword.Failed -> "danger",
    StatusKeyword.Sustained -> "danger",
    StatusKeyword.Skipped -> "warning",
    StatusKeyword.Pending -> "info",
    StatusKeyword.Loaded -> "success",
    StatusKeyword.Disabled -> "default")

  private val linkColor = Map(
    StatusKeyword.Passed -> "#3c763d",
    StatusKeyword.Failed -> "#a94442",
    StatusKeyword.Sustained -> "#a94442",
    StatusKeyword.Skipped -> "#8a6d3b",
    StatusKeyword.Pending -> "#31708f",
    StatusKeyword.Loaded -> "#3c763d",
    StatusKeyword.Disabled -> "grey"
  )

  private [report] def formatHtmlHead(pageTitle: String, rootPath: String): TypedTag[String] = {
    head(
      meta(charset := "utf-8"),
      meta(httpEquiv := "X-UA-Compatible", content := "IE=edge"),
      meta(name := "viewport", content := "width=device-width, initial-scale=1"),
      title := pageTitle,
      link(href := s"${rootPath}resources/css/bootstrap.min.css", rel := "stylesheet"),
      link(href := s"${rootPath}resources/css/gwen.css", rel := "stylesheet"),
      script(src := s"${rootPath}resources/js/jquery.min.js"),
      script(src := s"${rootPath}resources/js/bootstrap.min.js")
    )
  }
  
  private [report] def formatReportHeader(info: GwenInfo, heading: String, path: String, rootPath: String): TypedTag[String] = {
    val implVersion = s"v${info.implVersion}"
    table(width := "100%", attr("cellpadding") := "5",
      tr(
        td(width := "100px",
          a(href := info.gwenHome,
            img(src := s"${rootPath}resources/img/gwen-logo.png", border := "0", width := "82px")
          )
        ),
        td(
          h3(heading),
          path
        ),
        td(attr("align") := "right",
          h3(raw("&nbsp;")),
          a(href := info.implHome,
            span(`class` := "badge", style := "background-color: #1f23ae;", 
              info.implName
            )
          ),
          p(
            small(style := "white-space: nowrap; color: #1f23ae; padding-right: 7px;",
              info.releaseNotesUrl map { url => a(href := url, implVersion) } getOrElse implVersion
            )
          )
        )
      )
    )
  }
         
  private [report] def formatSummaryStatusHeader(summary: ResultsSummary): TypedTag[String] = {
    
    val status = summary.evalStatus.status
    val sustainedCount = summary.sustainedCount

    ol(`class` := "breadcrumb", style := "padding-right: 20px;",
      li(style := "color: gray",
        span(`class` := "caret-left", style := "color: #f5f5f5;"),
        " Summary"
      ),
      formatBadgeStatus(status, false, sustainedCount),
      formatDateStatus("Started", summary.started),
      formatDateStatus("Finished", summary.finished),
      formatElapsedStatus(summary.elapsedTime)
    )
  }

  private [report] def formatDetailStatusHeader(unit: FeatureUnit, result: SpecResult, rootPath: String, breadcrumbs: List[(String, File)], screenshots: List[File], linkToError: Boolean): TypedTag[String] = {
    
    val status = result.evalStatus.status
    val sustainedCount = result.sustainedCount
    val renderErrorLink = linkToError && (status == StatusKeyword.Failed || sustainedCount > 0)

    ol(`class` := "breadcrumb", style := "padding-right: 20px;",
      for ((text, reportFile) <- breadcrumbs) 
      yield
      li(
        span(`class` := "caret-left"),
        raw("&nbsp;"),
        a(href := s"${if (text == "Summary") rootPath else { if (result.isMeta) "../" else "" }}${reportFile.getName}",
          text
        )
      ),
      formatBadgeStatus(status, renderErrorLink, sustainedCount),
      formatDateStatus("Started", result.started),
      formatDateStatus("Finished", result.finished),
      if (GwenSettings.`gwen.report.slideshow.create` && screenshots.nonEmpty) {
        li(
          raw(formatSlideshow(screenshots, result.spec, unit, rootPath))
        )
      },
      formatElapsedStatus(result.elapsedTime)
    )

  }

  private def formatBadgeStatus(status: StatusKeyword.Value, renderErrorLink: Boolean, sustainedCount: Int): TypedTag[String] = {
    val sustainedError = s"${sustainedCount} sustained error${if (sustainedCount > 1) "s" else ""}"
    li(
      span(`class` := s"badge badge-${cssStatus(status)}",
        if (renderErrorLink && status == StatusKeyword.Failed) {
          Seq(
            a(id := "failed-link", href := "#", style := "color:white;",
              status.toString
            ),
            script(
              formatFailedLinkScript("failed")
            )
          )
        } else {
          status.toString
        }
      ),
      if (sustainedCount > 0) {
        small(
          span(`class` := "grayed", 
            " with "
          ),
          span(`class` := "badge badge-danger",
            if (renderErrorLink) {
              Seq(
                a(id := "sustained-link", href := "#", style := "color:white;",
                  sustainedError
                ),
                script(
                  formatFailedLinkScript("sustained")
                )
              )
            } else {
              sustainedError
            }
          )
        )
      }
    )
  }

  private def formatDateStatus(label: String, date: Date): TypedTag[String] = {
    li(
      small(
        span(`class` := "grayed", s"$label: "),
        date.toString
      )
    )
  }

  private def formatElapsedStatus(elapsedTime: Duration): TypedTag[String] = {
    span(`class` := "pull-right",
      small(formatDuration(elapsedTime))
    )
  }

  private def formatFailedLinkScript(statusType: String): String = {
    s"""|$$(document).ready(function() {
        |  $$('#${statusType}-link').click(
        |    function(e) {
        |      e.preventDefault();
        |      $$('html, body').animate({scrollTop:$$('.badge-${statusType}-issue').closest('.panel').offset().top}, 500);
        |    }
        |  );
        |});""".stripMargin
  }

  private def formatSlideshow(screenshots: List[File], spec: Spec, unit: FeatureUnit, rootPath: String) = {
    s"""
  <div class="modal fade" id="slideshow" tabindex="-1" role="dialog" aria-labelledby="slideshowLabel" aria-hidden="true">
  <div class="modal-dialog" style="width: 60%;">
  <div class="modal-content">
    <div class="modal-body">
    <a href="${HtmlSlideshowConfig.getReportDetailFilename(spec, unit.dataRecord).get}.${HtmlSlideshowConfig.fileExtension.get}" id="full-screen">Full Screen</a>
    <a href="#" title="Close"><span id="close-btn" class="pull-right glyphicon glyphicon-remove-circle" aria-hidden="true"></span></a>
    ${HtmlSlideshowFormatter.formatSlideshow(screenshots, rootPath).render}
   </div>
  </div>
  </div>
  </div>
  <button type="button" class="btn btn-default btn-lg" data-toggle="modal" data-target="#slideshow">
    Slideshow
  </button>
  <script>
    $$('#close-btn').click(function(e) { e.preventDefault(); $$('#slideshow').modal('hide'); });
    $$('#full-screen').click(function(e) { $$('#close-btn').click(); });
    $$('#slideshow').on('show.bs.modal', function (e) { $$('#slides').reel('frame', 1); stop(); });
    $$('#slideshow').on('hide.bs.modal', function (e) { $$('#slides').trigger('stop') });
    $$('#slideshow').on('hidden.bs.modal', function (e) { $$('#slides').trigger('stop') });
  </script>
  """
  }

  private def noOfKeywordPixels(steps: List[Step]): Int = steps match {
    case Nil => 9
    case _ => steps.map(_.keyword.length).max * 9
  }
          
}