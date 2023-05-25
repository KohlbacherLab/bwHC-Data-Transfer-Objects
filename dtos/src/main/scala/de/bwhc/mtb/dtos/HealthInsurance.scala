package de.bwhc.mtb.dtos


import play.api.libs.json.Json


object HealthInsurance
{

  case class Id(value: String) extends AnyVal

  implicit val formatId = Json.valueFormat[Id]
}

