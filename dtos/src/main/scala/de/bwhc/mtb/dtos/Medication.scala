package de.bwhc.mtb.dtos



import play.api.libs.json.Json



object Medication
{

  case class Code(value: String) extends AnyVal

  implicit val format = Json.valueFormat[Code]

  implicit val atcSystem =
    de.bwhc.mtb.dtos.Coding.System[Medication.Code]("ATC")


  object System extends Enumeration
  {
    val ATC          = Value
    val Unregistered = Value

    implicit val format = Json.formatEnum(this)
  }

  case class Coding
  (
    code: Code,
    system: System.Value,
    display: Option[String],
    version: Option[String]
  )

  object Coding {
    implicit val format = Json.format[Coding]
  }

}

