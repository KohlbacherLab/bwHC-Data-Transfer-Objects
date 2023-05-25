package de.bwhc.mtb.dtos


import play.api.libs.json.Json


final case class ZPM(value: String) extends AnyVal

object ZPM
{
  implicit val format = Json.valueFormat[ZPM]
}
