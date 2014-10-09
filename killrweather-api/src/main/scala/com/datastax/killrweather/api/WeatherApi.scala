/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.killrweather.api

import com.datastax.killrweather.Weather
import org.joda.time.DateTime

object WeatherApi {
  import Weather._

  sealed trait Aggregate extends DataResponse

  class WeatherStationId private (val value: String) extends AnyVal {
    override def toString: String = s"$value"
  }

  object WeatherStationId {
    import javax.servlet.http.HttpServletRequest
    import scalaz._

    val HttpHeader = "X-WEATHER-STATION-ID"

    def apply(value: String): Validation[String, WeatherStationId] =
      if (regex.pattern.matcher(value).matches) Success(new WeatherStationId(value.toLowerCase))
      else Failure(s"invalid Id '$value'")

    def apply(request: HttpServletRequest): Option[Validation[String, WeatherStationId]] =
      Option(request.getHeader(HttpHeader)) map (id => WeatherStationId(id))

    protected def stationId(request: HttpServletRequest): Option[String] =
      for {
        validated <- WeatherStationId(request)
        id <- validated.toOption
      } yield id.value

    private val regex = """[0-9]+:[0-9]+""".r
  }

}
