
import com.typesafe.sbt.license.{LicenseInfo, DepModuleInfo}

import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.CommentStyle
import scala.util.matching.Regex

// use `sbt createHeaders` to update source code headers.

licenses in GlobalScope += "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")

val license =
  s"""|Copyright 2017 California Institute of Technology ("Caltech").
      |U.S. Government sponsorship acknowledged.
      |
      |Licensed under the Apache License, Version 2.0 (the "License");
      |you may not use this file except in compliance with the License.
      |You may obtain a copy of the License at
      |
      |    http://www.apache.org/licenses/LICENSE-2.0
      |
      |Unless required by applicable law or agreed to in writing, software
      |distributed under the License is distributed on an "AS IS" BASIS,
      |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |See the License for the specific language governing permissions and
      |limitations under the License.
      |License Terms
      |""".stripMargin

startYear := Some(2017)

headerLicense := Some(HeaderLicense.ALv2("2017", license))

headerMappings := headerMappings.value +
  (HeaderFileType.scala -> CommentStyle.CStyleBlockComment)

licenseReportTitle := "LicenseReportOfAggregatedSBTPluginsAndLibraries"

licenseSelection += LicenseCategory("EPL", Seq("Eclipse Public License"))

// Add style rules to the report.
licenseReportStyleRules := Some("table, th, td {border: 1px solid black;}")

// The ivy configurations we'd like to grab licenses for.
licenseConfigurations := Set("compile", "provided")

// Override the license information from ivy, if it's non-existent or wrong
licenseOverrides := {
  case DepModuleInfo("com.jsuereth", _, _) =>
    LicenseInfo(LicenseCategory.BSD, "BSD-3-Clause", "http://opensource.org/licenses/BSD-3-Clause")
}

licenseReportTypes := Seq(Html)
