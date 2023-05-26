package de.bwhc.mtb.dtos



import java.time.{YearMonth,LocalDate}

import play.api.libs.json.{
  Json,
  JsString,
  Format,
  Reads,
  Writes,
  JsSuccess,
  JsError
}


final case class Patient
(
  id: Patient.Id,
  gender: Gender.Value,
  birthDate: Option[YearMonth],
  managingZPM: Option[ZPM],
  insurance: Option[HealthInsurance.Id],
  dateOfDeath: Option[YearMonth]
)


object Patient
{

  case class Id(value: String) extends AnyVal


  import java.time.{LocalDate,YearMonth}
  import java.time.format.DateTimeFormatter
  import scala.util.Try


  private val yyyyMM    = "yyyy-MM"
  private val yyyyMMFormatter = DateTimeFormatter.ofPattern(yyyyMM)


  implicit val formatYearMonth: Format[YearMonth] =
    Format(
      Reads(
        js =>
          for {
            s <- js.validate[String]
            result <-
              Try(
                YearMonth.parse(s,yyyyMMFormatter)
              )
              .orElse(
                Try(LocalDate.parse(s,DateTimeFormatter.ISO_LOCAL_DATE))
                  .map(d => YearMonth.of(d.getYear,d.getMonth))
              )
              .map(JsSuccess(_))
              .getOrElse(JsError(s"Invalid Year-Month value $s; expected format $yyyyMM (or $yyyyMM-DD as fallback)") )
          } yield result
      ),
      Writes(
        d => JsString(yyyyMMFormatter.format(d))
      )
    )



  implicit val formatId = Json.valueFormat[Id]

  implicit val format = Json.format[Patient]
}
