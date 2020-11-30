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

package gwen.report.portal

import gwen._
import RPConfig._

import java.io.File

object RPSettings {

  private val rerun: Boolean = `rp.rerun`
  private val rerunOf: Option[String] = `rp.rerun.of`
  private val rerunFile: File = new File("target/.rp-rerun.properties")

  def init(): Unit = {

    // load mandatory properties (will error if not found)
    `rp.endpoint`
    `rp.launch`
    `rp.project`
    `rp.key`

    // load rerun settings
    if (rerun && rerunOf.isEmpty && rerunFile.exists()) {
      Settings.loadAll(List(rerunFile))
    }
    
  }

  def writeRerunFile(uuid: String): Unit = {
    val content = 
      s"""|
          |# --------------------------------------------
          |# Report Portal Rerun File (generated by Gwen)
          |# --------------------------------------------
          |
          |# ID of last report portal launch. This property is implicitly loaded when 
          |# Gwen is launched with the rp.rerun=true property to facilitate rerun/merge 
          |# of the current execution results into the previous launch in report portal.
          |rp.rerun.of = $uuid
          |""".stripMargin
    rerunFile.writeText(content)
  }

  /* Provides access to the mandatory `rp.endpoint` report portal setting. */
  def `rp.endpoint`: String = Settings.get("rp.endpoint")

  /* Provides access to the mandatory `rp.launch` report portal setting. */
  def `rp.launch`: String = Settings.get("rp.launch")

  /* Provides access to the mandatory `rp.project` report portal setting. */
  def `rp.project`: String = Settings.get("rp.project")

  /* Provides access to the mandatory `rp.uuid` or `rp.api.key` report portal setting. */
  def `rp.key`: String = Settings.getOpt("rp.api.key").getOrElse(Settings.get("rp.uuid"))

  /* Provides access to the optional `rp.rerun` report portal setting. */
  def `rp.rerun`: Boolean = Settings.getOpt("rp.rerun").map(_.toBoolean).getOrElse(false)

  /* Provides access to the optional `rp.rerun.of` report portal setting. */
  def `rp.rerun.of`: Option[String] = Settings.getOpt("rp.rerun.of")

  /**
   * Provides access to the `gwen.rp.send.meta` property setting used to 
   * determine whether or not Meta specs are sent to the Report Portal (default value is `false`). 
   */
  def `gwen.rp.send.meta`: Boolean = Settings.getOpt("gwen.rp.send.meta").map(_.toBoolean).getOrElse(false)

  /**
   * Provides access to the `gwen.rp.send.stepDefs` property setting used to 
   * determine how step defs are reported. Options include: inlined, nested, or none (default is `none`). 
   */
  def `gwen.rp.send.stepDefs`: StepDefFormat.Value = Settings.getOpt("gwen.rp.send.stepDefs").map(_.toLowerCase).map(StepDefFormat.withName).getOrElse(StepDefFormat.none)

  /**
   * Provides access to the `gwen.rp.send.failed.StepDefs` property setting used to 
   * determine how failed step defs are reported. Options include: inlined, nested, or none (default is `inlined`). 
   * This setting is only honoured only if gwen.rp.stepDefs = none, otherwhise it takes on the 
   * same value as `gwen.rp.send.StepDefs`.
   */
  def `gwen.rp.send.failed.stepDefs`: StepDefFormat.Value = {
    Settings.getOpt("gwen.rp.send.failed.stepDefs").map(_.toLowerCase).map(StepDefFormat.withName).getOrElse(StepDefFormat.inlined)
  }

  /**
   * Provides access to the `gwen.rp.send.failed.errorTrace` property setting used to 
   * determine how error traces are reported. Options include: inlined, attached, or none (default value is `attached`). 
   */
  def `gwen.rp.send.failed.errorTrace`: ErrorReportingMode.Value = Settings.getOpt("gwen.rp.send.failed.errorTrace").map(_.toLowerCase).map(ErrorReportingMode.withName).getOrElse(ErrorReportingMode.attached)

  /**
   * Provides access to the `gwen.rp.send.failed.envTrace` property setting used to 
   * determine how environment traces are reported. Options include: inlined, attached, or none (default value is `attached`). 
   */
  def `gwen.rp.send.failed.envTrace`: ErrorReportingMode.Value = Settings.getOpt("gwen.rp.send.failed.envTrace").map(_.toLowerCase).map(ErrorReportingMode.withName).getOrElse(ErrorReportingMode.attached)

  /**
   * Provides access to the `gwen.rp.send.failed.hierarchy` property setting used to 
   * determine how failed step hierarchies are reported. Options include: inlined, attached, or none (default value is `attached`). 
   */
  def `gwen.rp.send.failed.hierarchy`: ErrorReportingMode.Value = Settings.getOpt("gwen.rp.send.failed.hierarchy").map(_.toLowerCase).map(ErrorReportingMode.withName).getOrElse(ErrorReportingMode.attached)

  /**
   * Provides access to the `gwen.rp.append.failed.msg.toStepNodes` property setting used to 
   * determine which step nodes in a failed call chain will have the error message appended 
   * to their descriptions (default is leaf).
   */
  def `gwen.rp.append.failed.msg.toStepNodes`: StepNodes.Value = Settings.getOpt("gwen.rp.append.failed.msg.toStepNodes").map(_.toLowerCase).map(StepNodes.withName).getOrElse(StepNodes.leaf)
  
  /**
   * Provides access to the `gwen.rp.send.breadcrumb.atts` property setting used to 
   * determine whether to send breadcrumb (feature, rule, scenario, step names) attributes to reported feature nodes.
   */
  def `gwen.rp.send.breadcrumb.atts`: Boolean = Settings.getOpt("gwen.rp.send.breadcrumb.atts").map(_.toBoolean).getOrElse(false)

}
