/*
 * Copyright 2015-2017 Branko Juric, Brady Wood
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
import gwen.Errors.AmbiguousCaseException
import gwen.Position

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import java.io.File

class SpecNormaliserTest extends FlatSpec with Matchers with SpecNormaliser with GherkinParser with GwenTestModel {

  private val parse = parseFeatureSpec(_: String)

  val background = Background(
    "background",
    List("Initialise"),
    List(Step(StepKeyword.Given.toString, "background step 1", Passed(2)))
  )

  "Feature with no background and no step defs" should "normalise without error" in {
    val feature = FeatureSpec(
    Feature("feature1", Nil),
      None,
      List(
      Scenario(List[Tag](), "scenario1", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      )),
      Nil,
      None,
      Nil)

    val result = normalise(feature, None, None)

    val scenario = result.scenarios(0)
    scenario.tags should be(Nil)
    scenario.name should be("scenario1")
    scenario.background should be(None)
    scenario.description should be(Nil)
    scenario.steps(0).toString should be("Given step 1")
    scenario.steps(1).toString should be("When step 2")
    scenario.steps(2).toString should be("Then step 3")
  }

  "Feature with background and no step defs" should "normalise without error" in {
    val feature = FeatureSpec(
      Feature("feature1", Nil),
      Some(background),
      List(
        Scenario(List[Tag](), "scenario1", Nil, None, List(
          Step(StepKeyword.Given.toString, "step 1", Passed(2)),
          Step(StepKeyword.When.toString, "step 2", Passed(1)),
          Step(StepKeyword.Then.toString, "step 3", Passed(2)))
        )),
        Nil,
        None,
        Nil)

    val result = normalise(feature, None, None)

    result.background should be (None)

    val scenario = result.scenarios(0)

    scenario.tags should be(Nil)
    scenario.name should be("scenario1")
    scenario.background should be (Some(background))
    scenario.description should be(Nil)
    scenario.steps(0).toString should be("Given step 1")
    scenario.steps(1).toString should be("When step 2")
    scenario.steps(2).toString should be("Then step 3")
  }
  
  "StepDef without background and one step def" should "normalise without error" in {
    val meta = FeatureSpec(
    Feature("meta1", Nil), None, List(
      Scenario(List(Tag("@StepDef")), "stepdef1", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      )), Nil, None, Nil)

    val result = normalise(meta, None, None)

    val scenario = result.scenarios(0)
    scenario.tags.map(_.name) should be(List("StepDef"))
    scenario.name should be("stepdef1")
    scenario.background should be(None)
    scenario.description should be(Nil)
    scenario.steps(0).toString should be("Given step 1")
    scenario.steps(1).toString should be("When step 2")
    scenario.steps(2).toString should be("Then step 3")
  }

  "StepDef with background and one step def" should "normalise without error" in {
    val meta = FeatureSpec(
      Feature("meta1", Nil), Some(background), List(
        Scenario(List(Tag("@StepDef")), "stepdef1", Nil, None, List(
          Step(StepKeyword.Given.toString, "step 1", Passed(2)),
          Step(StepKeyword.When.toString, "step 2", Passed(1)),
          Step(StepKeyword.Then.toString, "step 3", Passed(2)))
        )), Nil, None, Nil)

    val result = normalise(meta, None, None)
    result.background should be (None)

    val scenario = result.scenarios(0)
    scenario.tags.map(_.name) should be(List("StepDef"))
    scenario.name should be("stepdef1")
    scenario.background should be(None)
    scenario.description should be(Nil)
    scenario.steps(0).toString should be("Given step 1")
    scenario.steps(1).toString should be("When step 2")
    scenario.steps(2).toString should be("Then step 3")
  }
  
  "Meta with multiple unique step defs" should "normalise without error" in {
    val meta = FeatureSpec(
    Feature("meta1", Nil), None, List(
      Scenario(List(Tag("@StepDef")), "stepdef1", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      ),
      Scenario(List(Tag("@StepDef")), "stepdef2", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      )), Nil, None, Nil)
  normalise(meta, None, None)
  }
  
  "Meta with duplicate step def" should "error" in {
    val meta = FeatureSpec(
    Feature("meta1", Nil), None, List(
      Scenario(List(Tag("@StepDef")), "stepdef1", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      ),
      Scenario(List(Tag("@StepDef")), "stepdef1", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      )), Nil, None, Nil)
      
  intercept[AmbiguousCaseException] {
    normalise(meta, None, None)
    }
  }
  
  "Meta with duplicate step def with params" should "error" in {
    val meta = FeatureSpec(
    Feature("meta1", Nil), None, List(
      Scenario(List(Tag("@StepDef")), "stepdef <number>", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      ),
      Scenario(List(Tag("@StepDef")), "stepdef <index>", Nil, None, List(
        Step(StepKeyword.Given.toString, "step 1", Passed(2)),
        Step(StepKeyword.When.toString, "step 2", Passed(1)),
        Step(StepKeyword.Then.toString, "step 3", Passed(2)))
      )), Nil, None, Nil)
      
    intercept[AmbiguousCaseException] {
      normalise(meta, None, None)
    }
  }
  
  "Data driven feature with csv file and background" should "normalise without error" in {
    val feature = FeatureSpec(
    Feature("About me", Nil), Some(background), List(
      Scenario(List[Tag](), "What am I?", Nil, None, List(
        Step(StepKeyword.Given.toString, "I am ${my age} year(s) old"),
        Step(StepKeyword.When.toString, "I am a ${my gender}"),
        Step(StepKeyword.Then.toString, "I am a ${my age} year old ${my title}"))
      )), Nil, None, Nil)
    val data = List(("my age", "18"), ("my gender", "male"), ("my title", "Mr"))
    val dataRecord = new DataRecord("AboutMe.csv", 1, data)
    val result = normalise(feature, None, Some(dataRecord))
    result.background should be (None)
    result.feature.name should be ("About me [1]")
    result.scenarios.length should be (1)
    result.scenarios(0).background.get.name should be (s"${background.name} (plus input data)")
    result.scenarios(0).background.get.description should be (List("Initialise", """@Data(file="AboutMe.csv", record=1)"""))
    result.scenarios(0).background.get.steps.size should be (4)
    result.scenarios(0).background.get.steps(0).toString should be ("""Given my age is "18"""")
    result.scenarios(0).background.get.steps(1).toString should be ("""And my gender is "male"""")
    result.scenarios(0).background.get.steps(2).toString should be ("""And my title is "Mr"""")
    result.scenarios(0).background.get.steps(3).toString should be ("""And background step 1""")
    result.scenarios(0).name should be ("What am I?")
    result.scenarios(0).description should be (Nil)
    result.scenarios(0).steps(0).toString should be ("""Given I am ${my age} year(s) old""")
    result.scenarios(0).steps(1).toString should be ("""When I am a ${my gender}""")
    result.scenarios(0).steps(2).toString should be ("""Then I am a ${my age} year old ${my title}""")
  }

  "Data driven feature with csv file and no background" should "normalise without error" in {
    val feature = FeatureSpec(
    Feature("About me", Nil), None, List(
      Scenario(List[Tag](), "What am I?", Nil, None, List(
        Step(StepKeyword.Given.toString, "I am ${my age} year(s) old"),
        Step(StepKeyword.When.toString, "I am a ${my gender}"),
        Step(StepKeyword.Then.toString, "I am a ${my age} year old ${my title}"))
      )), Nil, None, Nil)
    val data = List(("my age", "18"), ("my gender", "male"), ("my title", "Mr"))
    val dataRecord = new DataRecord("AboutMe.csv", 1, data)
    val result = normalise(feature, None, Some(dataRecord))
    result.background should be (None)
    result.feature.name should be ("About me [1]")
    result.scenarios.length should be (1)
    result.scenarios(0).background.get.name should be ("Input data")
    result.scenarios(0).background.get.description should be (List("""@Data(file="AboutMe.csv", record=1)"""))
    result.scenarios(0).background.get.steps.size should be (3)
    result.scenarios(0).background.get.steps(0).toString should be ("""Given my age is "18"""")
    result.scenarios(0).background.get.steps(1).toString should be ("""And my gender is "male"""")
    result.scenarios(0).background.get.steps(2).toString should be ("""And my title is "Mr"""")
    result.scenarios(0).name should be ("What am I?")
    result.scenarios(0).description should be (Nil)
    result.scenarios(0).steps(0).toString should be ("""Given I am ${my age} year(s) old""")
    result.scenarios(0).steps(1).toString should be ("""When I am a ${my gender}""")
    result.scenarios(0).steps(2).toString should be ("""Then I am a ${my age} year old ${my title}""")
  }

  "Valid scenario outline" should "normalise" in {

    val featureString = """
    Feature: Outline

    Background: background
       Given background step 1

    @UnitTest
    Scenario Outline: Joining <string 1> and <string 2> should yield <result>

    Substituting..
    string 1 = <string 1>
    string 2 = <string 2>
    result = <result>

    Given string 1 is "<string 1>"
      And string 2 is "<string 2>"
     When I join the two strings
     Then the result should be "<result>"

    Examples: Compound words

      | string 1 | string 2 | result     |
      | basket   | ball     | basketball |
      | any      | thing    | anything   |

    Examples: Nonsensical compound words

      Words that don't make any sense at all
      (for testing multiple examples)

      | string 1 | string 2 | result   |
      | howdy    | doo      | howdydoo |
      | yep      | ok       | yepok    |

    Examples:

      | string 1 | string 2 | result   |
      | ding     | dong     | dingdong |
    """

    val rootDir: File = new File("target" +  File.separator + this.getClass.getSimpleName)
    val file = new File(rootDir.getPath, "outline.feature")
    file.getParentFile.mkdirs()
    file.createNewFile()
    file.writeText(featureString)

    val feature = parseFeatureFile(file).get

    val result = normalise(feature, Some(file), None)
    result.sourceRef.get.pos should be (Position(2, 5))
    result.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:2")
    result.sourceRef.get.nodePath.get should be ("/target/SpecNormaliserTest/outline.feature/Outline[1]")
    result.feature.sourceRef.get.pos should be (Position(2, 5))
    result.feature.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:2")
    result.feature.sourceRef.get.nodePath.get should be ("/target/SpecNormaliserTest/outline.feature/Outline[1]")

    result.background should be (None)

    val outline = result.scenarios(0)

    outline.sourceRef.get.pos should be (Position(8, 5))
    outline.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:8")
    outline.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]""")
    outline.tags.map(_.name) should be(List("UnitTest"))
    outline.tags(0).sourceRef.get.pos should be (Position(7, 5))
    outline.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    outline.name should be("Joining <string 1> and <string 2> should yield <result>")
    outline.background should be(None)
    outline.description should be(List("Substituting..", "string 1 = <string 1>", "string 2 = <string 2>", "result = <result>"))
    outline.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    outline.steps(0).sourceRef.get.pos should be(Position(15, 5))
    outline.steps(0).keyword should be(StepKeyword.Given.toString)
    outline.steps(0).name should be("""string 1 is "<string 1>"""")
    outline.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/string 1 is "<string 1>"[1]""")
    outline.steps(1).sourceRef.get.pos should be(Position(16, 7))
    outline.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    outline.steps(1).keyword should be(StepKeyword.And.toString)
    outline.steps(1).name should be("""string 2 is "<string 2>"""")
    outline.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/string 2 is "<string 2>"[1]""")
    outline.steps(2).sourceRef.get.pos should be(Position(17, 6))
    outline.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    outline.steps(2).keyword should be(StepKeyword.When.toString)
    outline.steps(2).name should be("I join the two strings")
    outline.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/I join the two strings[1]""")
    outline.steps(3).sourceRef.get.pos should be(Position(18, 6))
    outline.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    outline.steps(3).keyword should be(StepKeyword.Then.toString)
    outline.steps(3).name should be("""the result should be "<result>"""")
    outline.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/the result should be "<result>"[1]""")

    val examples = outline.examples
    examples.size should be(3)

    val example1 = examples(0)
    example1.sourceRef.get.pos should be (Position(20, 5))
    example1.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:20")
    example1.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]""")
    example1.name should be("Compound words")
    example1.description should be(Nil)
    example1.table.size should be(3)
    example1.table(0) should be((22, List("string 1", "string 2", "result")))
    example1.table(1) should be((23, List("basket", "ball", "basketball")))
    example1.table(2) should be((24, List("any", "thing", "anything")))
    example1.scenarios.size should be(2)

    val scenario1 = example1.scenarios(0)
    scenario1.sourceRef.get.pos should be (Position(23, 5))
    scenario1.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:23")
    scenario1.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]""")
    scenario1.tags.map(_.name) should be (List("UnitTest"))
    scenario1.tags(0).sourceRef.get.pos should be (Position(7, 5))
    scenario1.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    scenario1.name should be("Joining basket and ball should yield basketball -- Compound words")
    scenario1.background.get.sourceRef.get.pos should be (Position(4, 5))
    scenario1.background.get.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:4")
    scenario1.background.get.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/background[1]""")
    scenario1.background.get.name should be ("background")
    scenario1.background.get.steps(0).sourceRef.get.pos should be(Position(5, 8))
    scenario1.background.get.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:5")
    scenario1.background.get.steps(0).sourceRef.get.nodePath.get should be("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/background[1]/background step 1[1]""")
    scenario1.background.get.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario1.background.get.steps(0).name should be("background step 1")
    scenario1.description should be(List("Substituting..", "string 1 = basket", "string 2 = ball", "result = basketball"))
    scenario1.steps(0).sourceRef.get.pos should be(Position(15, 5))
    scenario1.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    scenario1.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/string 1 is "basket"[1]""")
    scenario1.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario1.steps(0).name should be("""string 1 is "basket"""")
    scenario1.steps(1).sourceRef.get.pos should be(Position(16, 7))
    scenario1.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    scenario1.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/string 2 is "ball"[1]""")
    scenario1.steps(1).keyword should be(StepKeyword.And.toString)
    scenario1.steps(1).name should be("""string 2 is "ball"""")
    scenario1.steps(2).sourceRef.get.pos should be(Position(17, 6))
    scenario1.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    scenario1.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/I join the two strings[1]""")
    scenario1.steps(2).keyword should be(StepKeyword.When.toString)
    scenario1.steps(2).name should be("I join the two strings")
    scenario1.steps(3).sourceRef.get.pos should be(Position(18, 6))
    scenario1.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    scenario1.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining basket and ball should yield basketball -- Compound words[1]/the result should be "basketball"[1]""")
    scenario1.steps(3).keyword should be(StepKeyword.Then.toString)
    scenario1.steps(3).name should be("""the result should be "basketball"""")

    val scenario2 = example1.scenarios(1)
    scenario2.sourceRef.get.pos should be (Position(24, 5))
    scenario2.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]""")
    scenario2.tags.map(_.name) should be (List("UnitTest"))
    scenario2.tags(0).sourceRef.get.pos should be (Position(7, 5))
    scenario2.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    scenario2.name should be("Joining any and thing should yield anything -- Compound words")
    scenario2.background.get.sourceRef.get.pos should be (Position(4, 5))
    scenario2.background.get.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:4")
    scenario2.background.get.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/background[1]""")
    scenario2.background.get.name should be ("background")
    scenario2.background.get.steps(0).sourceRef.get.pos should be (Position(5, 8))
    scenario2.background.get.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:5")
    scenario2.background.get.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/background[1]/background step 1[1]""")
    scenario2.background.get.steps(0).keyword should be (StepKeyword.Given.toString)
    scenario2.background.get.steps(0).name should be ("background step 1")
    scenario2.description should be(List("Substituting..", "string 1 = any", "string 2 = thing", "result = anything"))
    scenario2.steps(0).sourceRef.get.pos should be(Position(15, 5))
    scenario2.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    scenario2.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/string 1 is "any"[1]""")
    scenario2.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario2.steps(0).name should be("""string 1 is "any"""")
    scenario2.steps(1).sourceRef.get.pos should be(Position(16, 7))
    scenario2.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    scenario2.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/string 2 is "thing"[1]""")
    scenario2.steps(1).keyword should be(StepKeyword.And.toString)
    scenario2.steps(1).name should be("""string 2 is "thing"""")
    scenario2.steps(2).sourceRef.get.pos should be(Position(17, 6))
    scenario2.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    scenario2.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/I join the two strings[1]""")
    scenario2.steps(2).keyword should be(StepKeyword.When.toString)
    scenario2.steps(2).name should be("I join the two strings")
    scenario2.steps(3).sourceRef.get.pos should be(Position(18, 6))
    scenario2.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    scenario2.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Compound words[1]/Joining any and thing should yield anything -- Compound words[1]/the result should be "anything"[1]""")
    scenario2.steps(3).keyword should be(StepKeyword.Then.toString)
    scenario2.steps(3).name should be("""the result should be "anything"""")

    val example2 = examples(1)
    example2.sourceRef.get.pos should be (Position(26, 5))
    example2.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:26")
    example2.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]""")
    example2.name should be("Nonsensical compound words")
    example2.description.size should be(2)
    example2.description(0) should be("Words that don't make any sense at all")
    example2.description(1) should be("(for testing multiple examples)")
    example2.table.size should be(3)
    example2.table(0) should be((31, List("string 1", "string 2", "result")))
    example2.table(1) should be((32, List("howdy", "doo", "howdydoo")))
    example2.table(2) should be((33, List("yep", "ok", "yepok")))
    example2.scenarios.size should be(2)

    val scenario3 = example2.scenarios(0)
    scenario3.sourceRef.get.pos should be (Position(32, 5))
    scenario3.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:32")
    scenario3.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]""")
    scenario3.tags.map(_.name) should be (List("UnitTest"))
    scenario3.tags(0).sourceRef.get.pos should be (Position(7, 5))
    scenario3.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    scenario3.name should be("Joining howdy and doo should yield howdydoo -- Nonsensical compound words")
    scenario3.background.get.sourceRef.get.pos should be (Position(4, 5))
    scenario3.background.get.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:4")
    scenario3.background.get.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/background[1]""")
    scenario3.background.get.name should be ("background")
    scenario3.background.get.steps(0).sourceRef.get.pos should be(Position(5, 8))
    scenario3.background.get.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:5")
    scenario3.background.get.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/background[1]/background step 1[1]""")
    scenario3.background.get.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario3.background.get.steps(0).name should be("background step 1")
    scenario3.description should be(List("Substituting..", "string 1 = howdy", "string 2 = doo", "result = howdydoo"))
    scenario3.steps(0).sourceRef.get.pos should be(Position(15, 5))
    scenario3.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    scenario3.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/string 1 is "howdy"[1]""")
    scenario3.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario3.steps(0).name should be("""string 1 is "howdy"""")
    scenario3.steps(1).sourceRef.get.pos should be(Position(16, 7))
    scenario3.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    scenario3.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/string 2 is "doo"[1]""")
    scenario3.steps(1).keyword should be(StepKeyword.And.toString)
    scenario3.steps(1).name should be("""string 2 is "doo"""")
    scenario3.steps(2).sourceRef.get.pos should be(Position(17, 6))
    scenario3.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    scenario3.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/I join the two strings[1]""")
    scenario3.steps(2).keyword should be(StepKeyword.When.toString)
    scenario3.steps(2).name should be("I join the two strings")
    scenario3.steps(3).sourceRef.get.pos should be(Position(18, 6))
    scenario3.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    scenario3.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining howdy and doo should yield howdydoo -- Nonsensical compound words[1]/the result should be "howdydoo"[1]""")
    scenario3.steps(3).keyword should be(StepKeyword.Then.toString)
    scenario3.steps(3).name should be("""the result should be "howdydoo"""")

    val scenario4 = example2.scenarios(1)
    scenario4.sourceRef.get.pos should be (Position(33, 5))
    scenario4.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:33")
    scenario4.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]""")
    scenario4.tags.map(_.name) should be (List("UnitTest"))
    scenario4.tags(0).sourceRef.get.pos should be (Position(7, 5))
    scenario4.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    scenario4.name should be("Joining yep and ok should yield yepok -- Nonsensical compound words")
    scenario4.background.get.sourceRef.get.pos should be (Position(4, 5))
    scenario4.background.get.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:4")
    scenario4.background.get.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/background[1]""")
    scenario4.background.get.name should be ("background")
    scenario4.background.get.steps(0).sourceRef.get.pos should be(Position(5, 8))
    scenario4.background.get.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:5")
    scenario4.background.get.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/background[1]/background step 1[1]""")
    scenario4.background.get.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario4.background.get.steps(0).name should be("background step 1")
    scenario4.description should be(List("Substituting..", "string 1 = yep", "string 2 = ok", "result = yepok"))
    scenario4.steps(0).sourceRef.get.pos should be(Position(15, 5))
    scenario4.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    scenario4.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/string 1 is "yep"[1]""")
    scenario4.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario4.steps(0).name should be("""string 1 is "yep"""")
    scenario4.steps(1).sourceRef.get.pos should be(Position(16, 7))
    scenario4.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    scenario4.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/string 2 is "ok"[1]""")
    scenario4.steps(1).keyword should be(StepKeyword.And.toString)
    scenario4.steps(1).name should be("""string 2 is "ok"""")
    scenario4.steps(2).sourceRef.get.pos should be(Position(17, 6))
    scenario4.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    scenario4.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/I join the two strings[1]""")
    scenario4.steps(2).keyword should be(StepKeyword.When.toString)
    scenario4.steps(2).name should be("I join the two strings")
    scenario4.steps(3).sourceRef.get.pos should be(Position(18, 6))
    scenario4.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    scenario4.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/Nonsensical compound words[1]/Joining yep and ok should yield yepok -- Nonsensical compound words[1]/the result should be "yepok"[1]""")
    scenario4.steps(3).keyword should be(StepKeyword.Then.toString)
    scenario4.steps(3).name should be("""the result should be "yepok"""")

    val example3 = examples(2)
    example3.sourceRef.get.pos should be (Position(35, 5))
    example3.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:35")
    example3.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]/""")
    example3.name should be("")
    example3.description should be(Nil)
    example3.table.size should be(2)
    example3.table(0) should be((37, List("string 1", "string 2", "result")))
    example3.table(1) should be((38, List("ding", "dong", "dingdong")))
    example3.scenarios.size should be(1)

    val scenario5 = example3.scenarios(0)
    scenario5.sourceRef.get.pos should be (Position(38, 5))
    scenario5.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:38")
    scenario5.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]""")
    scenario5.tags.map(_.name) should be (List("UnitTest"))
    scenario5.tags(0).sourceRef.get.pos should be (Position(7, 5))
    scenario5.tags(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:7")
    scenario5.name should be("Joining ding and dong should yield dingdong")
    scenario5.background.get.sourceRef.get.pos should be (Position(4, 5))
    scenario5.background.get.sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:4")
    scenario5.background.get.sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/background[1]""")
    scenario5.background.get.name should be ("background")
    scenario5.background.get.steps(0).sourceRef.get.pos should be(Position(5, 8))
    scenario5.background.get.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:5")
    scenario5.background.get.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/background[1]/background step 1[1]""")
    scenario5.background.get.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario5.background.get.steps(0).name should be("background step 1")
    scenario5.description should be(List("Substituting..", "string 1 = ding", "string 2 = dong", "result = dingdong"))
    scenario5.steps(0).sourceRef.get.pos should be(Position(15, 5))
    scenario5.steps(0).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:15")
    scenario5.steps(0).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/string 1 is "ding"[1]""")
    scenario5.steps(0).keyword should be(StepKeyword.Given.toString)
    scenario5.steps(0).name should be("""string 1 is "ding"""")
    scenario5.steps(1).sourceRef.get.pos should be(Position(16, 7))
    scenario5.steps(1).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:16")
    scenario5.steps(1).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/string 2 is "dong"[1]""")
    scenario5.steps(1).keyword should be(StepKeyword.And.toString)
    scenario5.steps(1).name should be("""string 2 is "dong"""")
    scenario5.steps(2).sourceRef.get.pos should be(Position(17, 6))
    scenario5.steps(2).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:17")
    scenario5.steps(2).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/I join the two strings[1]""")
    scenario5.steps(2).keyword should be(StepKeyword.When.toString)
    scenario5.steps(2).name should be("I join the two strings")
    scenario5.steps(3).sourceRef.get.pos should be(Position(18, 6))
    scenario5.steps(3).sourceRef.get.toString should be("target/SpecNormaliserTest/outline.feature:18")
    scenario5.steps(3).sourceRef.get.nodePath.get should be ("""/target/SpecNormaliserTest/outline.feature/Outline[1]/Joining <string 1> and <string 2> should yield <result>[1]//Joining ding and dong should yield dingdong[1]/the result should be "dingdong"[1]""")
    scenario5.steps(3).keyword should be(StepKeyword.Then.toString)
    scenario5.steps(3).name should be("""the result should be "dingdong"""")

    val scenarios = outline.examples.flatMap(_.scenarios)
    scenarios.size should be(5)
    scenarios(0) should be(scenario1)
    scenarios(1) should be(scenario2)
    scenarios(2) should be(scenario3)
    scenarios(3) should be(scenario4)
    scenarios(4) should be(scenario5)
  }

  "Scenario with identically named steps" should "return correct occurence number" in {

    val spec = 
      s"""|  Feature: feature containing scenario with identically named steps
          |
          | Scenario: scenario with identical steps
          |     Given step 1
          |      When identical
          |      Then step 3
          |       And identical
          |       And step 5
          |       And step 6
          |       And identical
          |""".stripMargin

    val feature = parse(spec).get
    val result = normalise(feature, None, None)
    val scenario = result.scenarios(0)

    scenario.steps(0).occurrenceIn(scenario) should be (1)
    scenario.steps(1).occurrenceIn(scenario) should be (1)
    scenario.steps(2).occurrenceIn(scenario) should be (1)
    scenario.steps(3).occurrenceIn(scenario) should be (2)
    scenario.steps(4).occurrenceIn(scenario) should be (1)
    scenario.steps(5).occurrenceIn(scenario) should be (1)
    scenario.steps(6).occurrenceIn(scenario) should be (3)
  }

  "Feature with identically named scenarios" should "return correct occurence number" in {

    val spec = 
      s"""|  Feature: feature containing identically named scenarios
          |
          | Scenario: scenario 1
          |     Given step 1
          |      Then step 2
          | Scenario: identical
          |     Given step 1
          |      Then step 2
          | Scenario: scenario 3
          |     Given step 1
          |      Then step 2
          | Scenario: scenario 4
          |     Given step 1
          |      Then step 2
          | Scenario: identical
          |     Given step 1
          |      Then step 2
          | Scenario: identical
          |     Given step 1
          |      Then step 2
          | Scenario: scenario 7
          |     Given step 1
          |      Then step 2
          |""".stripMargin

    val feature = parse(spec).get
    val result = normalise(feature, None, None)

    result.scenarios(0).occurrenceIn(result) should be (1)
    result.scenarios(1).occurrenceIn(result) should be (1)
    result.scenarios(2).occurrenceIn(result) should be (1)
    result.scenarios(3).occurrenceIn(result) should be (1)
    result.scenarios(4).occurrenceIn(result) should be (2)
    result.scenarios(5).occurrenceIn(result) should be (3)
    result.scenarios(6).occurrenceIn(result) should be (1)
  }

  "Feature with identically named outline examples" should "return correct occurence number" in {

    val spec = 
      s"""|  Feature: feature containing identically outline examples
          |
          | Scenario Outline: outline 1
          |     Given step using <name 1>
          |      Then step using <name 2>
          | Examples: identical
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          | Examples: examples 2
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          | Examples: identical
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          | Examples: identical
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          | Examples: identical
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          | Examples: examples 6
          |      | name 1 | name 2 |
          |      | data 1 | data 2 |
          |  
          |""".stripMargin

    val feature = parse(spec).get
    val result = normalise(feature, None, None)
    val outline = result.scenarios(0)

    outline.examples(0).occurrenceIn(outline) should be (1)
    outline.examples(1).occurrenceIn(outline) should be (1)
    outline.examples(2).occurrenceIn(outline) should be (2)
    outline.examples(3).occurrenceIn(outline) should be (3)
    outline.examples(4).occurrenceIn(outline) should be (4)
    outline.examples(5).occurrenceIn(outline) should be (1)
  }
  
}