/*
 * Copyright 2015 Branko Juric, Brady Wood
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

package gwen.eval.support

import gwen.errors._
import gwen.eval.EnvContext

/** Can be mixed into evaluation engines to provide Regex support. */
trait RegexSupport {
  this: EnvContext =>

  /**
    * Extracts a substring from a source string by regex. The value in the
    * first matching regex group is returned, otherwise an error is thrown.
    * 
    * @param regex the regex extractor pattern
    * @param source the source string
    * @return the extracted value
    * @throws gwen.errors.RegexException if the regex fails to evaluate
    */
  def extractByRegex(regex: String, source: String): String =
    evaluate(s"dryRun[regex:$regex") {
      regex.r.findFirstMatchIn(source).getOrElse(regexError(s"'Regex match '$regex' not found in '$source'")).group(1)
    }
    
}