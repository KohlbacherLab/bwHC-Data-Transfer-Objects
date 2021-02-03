package de.bwhc.mtb.data.entry.impl


import java.time.LocalDate
import java.time.temporal.Temporal

import scala.util.Either
import scala.concurrent.{
  ExecutionContext,
  Future
}

import cats.data.NonEmptyList
import cats.data.Validated

import de.bwhc.util.spi._
import de.bwhc.util.data.ClosedInterval
import de.bwhc.util.data.Validation._
import de.bwhc.util.data.Validation.dsl._

import de.bwhc.mtb.data.entry.dtos
import de.bwhc.mtb.data.entry.dtos._
import de.bwhc.mtb.data.entry.api.DataQualityReport

import de.bwhc.catalogs.icd
import de.bwhc.catalogs.icd._
import de.bwhc.catalogs.hgnc.{HGNCGene,HGNCCatalog}
import de.bwhc.catalogs.med.MedicationCatalog



trait DataValidator
{

  def check(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[Validated[DataQualityReport,MTBFile]]

}

trait DataValidatorProvider extends SPI[DataValidator]

object DataValidator extends SPILoader(classOf[DataValidatorProvider])



class DefaultDataValidator extends DataValidator
{

  import DefaultDataValidator._

  def check(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[Validated[DataQualityReport,MTBFile]] = {
    Future.successful(
      mtbfile.validate
        .leftMap(DataQualityReport(mtbfile.patient.id,_))
    )
  }

}


object DefaultDataValidator
{

  import DataQualityReport._
  import DataQualityReport.Issue._

  import cats.syntax.apply._
  import cats.syntax.traverse._
  import cats.syntax.validated._
  import cats.instances.list._
  import cats.instances.option._
  import cats.instances.set._



  type DataQualityValidator[T] = Validator[DataQualityReport.Issue,T]


  import java.time.temporal.Temporal

  implicit class TemporalFormattingOps[T <: Temporal](val t: T)
  {

    import java.time.{
      Instant,
      LocalDate,
      LocalDateTime
    }
    import java.time.format.DateTimeFormatter

     def toISOFormat: String = {
       t match {
         case ld:  LocalDate     => DateTimeFormatter.ISO_LOCAL_DATE.format(ld)
         case ldt: LocalDateTime => DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ldt)
         case t:   Instant       => DateTimeFormatter.ISO_INSTANT.format(t)
       }
     }

  }


  private def validReference[Ref](
    location: => Location,
  )(
    implicit ref: Ref
  ): DataQualityValidator[Ref] = {
    r =>
      r must equal (ref) otherwise (
        Fatal(s"Ungültige Referenz auf $r") at location
      )
  }

  private def validReference[Ref, C[X] <: Iterable[X]](
    refs: C[Ref]
  )(
    location: => Location,
  ): DataQualityValidator[Ref] = {
    r =>
      r must be (in (refs)) otherwise (
        Fatal(s"Ungültige Referenz auf $r") at location
      )
  }

  private def validReferences[Ref](
    location: => Location,
  )(
    implicit refs: Iterable[Ref]
  ): DataQualityValidator[List[Ref]] = {
    rs =>
      rs validateEach (
        r => r must be (in (refs)) otherwise (
          Fatal(s"Ungültige Referenz auf $r") at location
        )
      )
  }



  implicit val patientValidator: DataQualityValidator[Patient] = {

    case pat @ Patient(Patient.Id(id),_,birthDate,_,insurance,dod) =>

      (
        birthDate mustBe defined otherwise (
          Error("Fehlende Angabe: Geburtsdatum") at Location("Patient",id,"Geburtsdatum")
        ),

        insurance shouldBe defined otherwise (
          Warning("Fehlende Angabe: IK Krankenkasse") at Location("Patient",id,"IK Krankenkasse")
        ),

        dod map (
          date => date must be (before (LocalDate.now)) otherwise (
            Error(s"Ungültiges Todesdatum '${date.toISOFormat}': liegt in der Zukunft")
              at Location("Patient",id,"Todesdatum")
          )
        ) getOrElse (dod.validNel[Issue]),

        (birthDate, dod)
          .mapN(
            (b,d) =>
              d must be (after (b)) otherwise (
                Error(s"Ungültiges Todesdatum '${d.toISOFormat}': liegt vor Geburtsdatum ${b.toISOFormat}")
                  at Location("Patient",id,"Todesdatum")
              )
          )
          .getOrElse(LocalDate.now.validNel[Issue])
      )
      .mapN { case _: Product => pat }
  }


  implicit def consentValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[Consent] = {

    case consent @ Consent(id,patient,_) =>

      patient must be (validReference[Patient.Id](Location("Consent",id.value,"Patient"))) map (_ => consent)

  }


  implicit def episodeValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[MTBEpisode] = {
    case episode @ MTBEpisode(id,patient,period) =>

      patient must be (validReference[Patient.Id](Location("MTB-Episode",id.value,"Patient"))) map (_ => episode)

  }


  implicit lazy val icd10gmCatalog = ICD10GMCatalogs.getInstance.get

  implicit lazy val icdO3Catalog   = ICDO3Catalogs.getInstance.get


  implicit def icd10Validator(
    implicit
    catalog: ICD10GMCatalogs
  ): DataQualityValidator[Coding[ICD10GM]] = {

      case icd10 @ Coding(dtos.ICD10GM(code),_,version) =>

        version mustBe defined otherwise (
          Error("Fehlende ICD-10-GM Version") at Location("ICD-10-GM Coding","","Version")
        ) andThen (
          v =>
            attempt(icd.ICD10GM.Version(v.get)) otherwise (
              Error(s"Ungültige ICD-10-GM Version '${v.get}'") at Location("ICD-10-GM Coding","","Version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.codings(v).map(_.code.value)))
              otherwise (Error(s"Ungültiger ICD-10-GM Code '$code'") at Location("ICD-10-GM Coding","","Code"))
        ) map (c => icd10)

    }


  implicit def icdO3TValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3T]] = {

      case icdo3t @ Coding(ICDO3T(code),_,version) =>

        version mustBe defined otherwise (
          Error("Fehlende ICD-O-3 Version") at Location("ICD-O-3-T Coding","","Version")
        ) andThen (
          v => 
            attempt(icd.ICDO3.Version(v.get)) otherwise (
              Error(s"Fehlende ICD-O-3 Version '${v.get}'") at Location("ICD-O-3-T Coding","","Version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.topographyCodings(v).map(_.code.value)))
              otherwise (Error(s"Ungültiger ICD-O-3-T Code '$code'") at Location("ICD-O-3-T Coding","","Code"))
        ) map (c => icdo3t)

    }


  implicit def icdO3MValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3M]] = {

      case icdo3m @ Coding(ICDO3M(code),_,version) =>

        version mustBe defined otherwise (
          Error("Fehlende ICD-O-3 Version") at Location("ICD-O-3-M Coding","","Version")
        ) andThen (
          v =>
            attempt(icd.ICDO3.Version(v.get)) otherwise (
              Error(s"Ungültige ICD-O-3 Version '${v.get}'") at Location("ICD-O-3-M Coding","","Version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.morphologyCodings(v).map(_.code.value)))
              otherwise (Error(s"Ungültiger ICD-O-3-M Code '$code'") at Location("ICD-O-3-M Coding","","Code"))
        ) map (c => icdo3m)

    }


  implicit val medicationCatalog = MedicationCatalog.getInstance.get

  implicit def medicationValidator(
    implicit
    catalog: MedicationCatalog
  ): DataQualityValidator[Coding[Medication]] = {

    case medication @ Coding(Medication(atcCode),_,_) =>

      atcCode must be (in (catalog.entries.map(_.code.value))) otherwise (
        Error(s"Ungültiger ATC Medicationscode '$atcCode'") at Location("Medication Coding","","Code")
      ) map (c => medication)

  }



  implicit def diagnosisValidator(
    implicit
    patId: Patient.Id,
    specimenRefs: List[Specimen.Id],
    histologyRefs: List[HistologyReport.Id]
  ): DataQualityValidator[Diagnosis] = {

    case diag @ Diagnosis(Diagnosis.Id(id),patient,date,icd10,icdO3T,_,histologyReportRefs,_,glTreatmentStatus) =>

      implicit val diagId = diag.id

      (
        patient must be (validReference[Patient.Id](Location("Diagnose",id,"Patient"))),

        date shouldBe defined otherwise (Warning("Fehlende Angabe: Erstdiagnosedatum") at Location("Diagnose",id,"Datum")),

        icd10 mustBe defined otherwise (Error("Fehlende Angabe: ICD-10-GM Kodierung") at Location("Diagnose",id,"ICD-10"))
          andThen (_.get.validate.leftMap(_.map(_.copy(location = Location("Diagnose",id,"ICD-10"))))),

        icdO3T couldBe defined otherwise (Info("Fehlende ICD-O-3-T Kodierung") at Location("Diagnose",id,"ICD-O-3-T"))
          andThen (_.get.validate.leftMap(_.map(_.copy(location = Location("Diagnose",id,"ICD-O-3-T"))))),

        histologyReportRefs
          .map(_ must be (validReferences[HistologyReport.Id](Location("Diagnose",id,"Histologie-Berichte"))))
          .getOrElse(List.empty[HistologyReport.Id].validNel[Issue]), 

        glTreatmentStatus mustBe defined otherwise (
          Warning("Fehlende Angabe: Leitlinienbehandlungs-Status") at Location("Diagnose",id,"Leitlinienbehandlungs-Status")
        )

      )
      .mapN { case _: Product => diag }

  }


  implicit def famMemberDiagnosisValidator(
    implicit
    patId: Patient.Id,
  ): DataQualityValidator[FamilyMemberDiagnosis] = {

    diag =>
      diag.patient must be (validReference[Patient.Id](Location("Verwandtendiagnose",diag.id.value,"Patient"))) map (ref => diag)
  }


//  implicit val therapyLines = (0 to 9).map(TherapyLine(_))
  
  implicit def prevGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
//    therapyLines: Seq[TherapyLine]
  ): DataQualityValidator[PreviousGuidelineTherapy] = {

    case th @ PreviousGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,medication) =>
      (
        patient must be (validReference[Patient.Id](Location("Leitlinien-Therapie",id,"Patient"))),

        diag must be (validReference(diagnosisRefs)(Location("Leitlinien-Therapie",id,"Diagnose"))),

        therapyLine shouldBe defined otherwise (
          Warning("Fehlende Angabe: Therapielinie") at Location("Leitlinien-Therapie",id,"Therapielinie")
        ),

        medication.toList 
          .validateEach
          .leftMap(_.map(_.copy(location = Location("Leitlinien-Therapie",id,"Medication")))),
        
      )
      .mapN { case _: Product => th }
  }


  implicit def lastGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
//    therapyLines: Seq[TherapyLine],
    therapyRefs: Seq[TherapyId],
  ): DataQualityValidator[LastGuidelineTherapy] = {

    case th @ LastGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,period,medication,reasonStopped) =>
      (
        patient must be (validReference[Patient.Id](Location("Letzte Leitlinien-Therapie",id,"Patient"))),

        diag must be (validReference(diagnosisRefs)(Location("Letzte Leitlinien-Therapie",id,"Diagnose"))),

        period shouldBe defined otherwise (
          Warning("Fehlende Angabe: Therapie-Zeitraum (Anfangs-/Enddatum)") at Location("Letzte Leitlinien-Therapie",id,"Zeitraum")
        ) andThen (
          p => p.get.end shouldBe defined otherwise (Warning("Fehlende Angabe: Therapie-Enddatum") at Location("Letzte Leitlinien-Therapie",id,"Zeitraum"))
        ),

        therapyLine shouldBe defined otherwise (
          Warning("Fehlende Angabe: Therapielinie") at Location("Letzte Leitlinien-Therapie",id,"Therapielinie")
        ),
        
        medication.toList.validateEach
          .leftMap(_.map(_.copy(location = Location("Letzte Leitlinien-Therapie",id,"Medication")))),

        reasonStopped shouldBe defined otherwise (
          Warning("Fehlende Angabe: Abbruchsgrund") at Location("Letzte Leitlinien-Therapie",id,"Abbruchsgrund")
        ),

        th.id must be (in (therapyRefs)) otherwise (
          Warning("Fehlende Angabe: Response") at Location("Letzte Leitlinien-Therapie",id,"Response")
        )
      )
      .mapN { case _: Product => th }

  }


  implicit def ecogStatusValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[ECOGStatus] = {

    case pfSt @ ECOGStatus(id,patient,date,value) =>

      (
        patient must be (validReference[Patient.Id](Location("ECOG Status",id.value,"Patient"))),

        date mustBe defined otherwise (
          Error("Fehlende Angabe: Datum für ECOG Status Befund") at Location("ECOG Status",id.value,"Datum")
        ),
      )
      .mapN { case _: Product => pfSt }

  }


  implicit def specimenValidator(
    implicit
    patId: Patient.Id,
    icd10codes: Seq[ICD10GM]
  ): DataQualityValidator[Specimen] = {

    case sp @ Specimen(Specimen.Id(id),patient,icd10,typ,collection) =>
      (
        patient must be (validReference[Patient.Id](Location("Tumor-Probe",id,"Patient"))),

        icd10.validate
          andThen (
            icd =>
              icd.code must be (in (icd10codes)) otherwise (
                Fatal(s"Ungültige Referenz auf Entität ${icd.code.value}") at Location("Tumor-Probe",id,"Entität")
              )
          ),
  
        typ shouldBe defined otherwise (
          Warning(s"Fehlende Angabe: Art der Tumor-Probe") at Location("Tumor-Probe",id,"Art")
        ),

        collection shouldBe defined otherwise (
          Warning(s"Fehlende Angabe: Entnahme Tumor-Probe (Datum, Lokalisierung, Gewinnung)") at Location("Tumor-Probe",id,"Entnahme")
        )
       
      )
      .mapN { case _: Product => sp }

  }



  import scala.math.Ordering.Double.TotalOrdering

  private val tcRange = ClosedInterval(0.0 -> 1.0)

  implicit def tumorContentValidator(
    implicit specimens: Seq[Specimen.Id]
  ): DataQualityValidator[TumorCellContent] = {

    case tc @ TumorCellContent(TumorCellContent.Id(id),specimen,method,value) => {

      (
        value must be (in (tcRange)) otherwise (
          Error(s"Tumorzellgehalt-Wert '$value' (${value*100} %) nicht in Referenz-Bereich $tcRange")
            at Location("TumorContent",id,"value")
        ) map (_ => tc),

        specimen must be (validReference(specimens)(Location("Tumorzellgehalt",id,"Tumor-Probe")))
      )
      .mapN { case _: Product => tc }
  
    }
     
  }


  implicit def tumorMorphologyValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[TumorMorphology] = {

    case morph @ TumorMorphology(TumorMorphology.Id(id),patient,specimen,icdO3M,notes) =>

      (
        patient must be (validReference[Patient.Id](Location("Tumor-Morphologie-Befund (ICD-O-3-M)",id,"Patient"))),

        specimen must be (validReference(specimens)(Location("Tumor-Morphologie-Befund (ICD-O-3-M)",id,"Probe"))),
        
        icdO3M.validate
      )
      .mapN {case _: Product => morph }
      
  }


  import ValueSets._


  implicit def histologyReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReport] = {

    case histo @ HistologyReport(HistologyReport.Id(id),patient,specimen,date,morphology,tumorContent) =>

      val expectedMethod = TumorCellContent.Method.Histologic

      (
        patient must be (validReference[Patient.Id](Location("Histologie-Bericht",id,"Patient"))),

        specimen must be (validReference(specimens)(Location("Histologie-Bericht",id,"Specimen"))),

        date mustBe defined otherwise (Error("Fehlende Angabe: Datum") at Location("Histologie-Bericht",id,"Datum")),

        morphology mustBe defined otherwise (
          Warning("Fehlende Angabe: Tumor-Morphologie-Befund (ICD-O-3-M)") at Location("Histologie-Bericht",id,"Tumor-Morphologie")
        ) andThen (_.get validate),

        tumorContent mustBe defined otherwise (
          Error("Fehlende Angabe: Tumorzellgehalt") at Location("Histologie-Bericht",id,"Tumorzellgehalt")
        ) map (_.get) andThen (
          tc =>
            (
              tc.method must equal (expectedMethod)
                otherwise (
                  Error(s"Erwartete Tumorzellgehalt-Bestimmungsmethode '${ValueSet[TumorCellContent.Method.Value].displayOf(expectedMethod).get}'")
                    at Location("Histologie-Bericht",id,"Tumorzellgehalt")
                ),
              tc validate
            )
            .mapN { case _: Product => tc }
          ),
      )
      .mapN { case _: Product => histo }

  }


  implicit def molecularPathologyValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[MolecularPathologyFinding] = {

    case molPath @ MolecularPathologyFinding(MolecularPathologyFinding.Id(id),patient,specimen,_,date,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molekular-Pathologie-Befund",id,"Patient"))),

        specimen must be (validReference(specimens)(Location("Molekular-Pathologie-Befund",id,"Probe"))),

        date mustBe defined otherwise (Error("Fehlende Angabe: Datum") at Location("Molekular-Pathologie-Befund",id,"Datum")),

      )
      .mapN { case _: Product => molPath }

  }


  private val hgncCatalog = HGNCCatalog.getInstance.get

  import scala.language.implicitConversions

  implicit def toHGNCGeneSymbol(gene: Variant.Gene): HGNCGene.Symbol =
    HGNCGene.Symbol(gene.value)

  private def validGeneSymbol(
    location: => Location
  ): DataQualityValidator[Variant.Gene] = 
    symbol => 
      hgncCatalog.geneWithSymbol(symbol) mustBe defined otherwise (
        Error(s"Ungültiges Gen-Symbol ${symbol.value}") at location
      ) map (_ => symbol)
  


  implicit def ngsReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id],
  ): DataQualityValidator[SomaticNGSReport] = {


    case ngs @
      SomaticNGSReport(SomaticNGSReport.Id(id),patient,specimen,date,_,_,tumorContent,brcaness,msi,tmb,optSnvs,_,_,_,_) => {

      import SomaticNGSReport._

      val brcanessRange = ClosedInterval(0.0 -> 1.0)
      val msiRange      = ClosedInterval(0.0 -> 2.0)
      val tmbRange      = ClosedInterval(0.0 -> 1e6)  // TMB in mut/MBase, so [0,1000000]

      val expectedMethod = TumorCellContent.Method.Bioinformatic

      (
        patient must be (validReference[Patient.Id](Location("Somatischer NGS-Befund",id,"Patient"))),

        specimen must be (validReference(specimens)(Location("Somatischer NGS-Befund",id,"Probe"))),

        tumorContent.method must equal (expectedMethod)
          otherwise (Error(s"Erwartete Tumorzellgehalt-Bestimmungsmethode '${ValueSet[TumorCellContent.Method.Value].displayOf(expectedMethod).get}'")
            at Location("Somatischer NGS-Befund",id,"Tumorzellgehalt")),

        tumorContent validate,
       
        brcaness shouldBe defined otherwise (Info("Fehlende Angabe: BRCAness Wert") at Location("Somatischer NGS-Befund",id,"BRCAness"))
          andThen {
            opt =>
              opt.get.value must be (in (brcanessRange)) otherwise (
                  Error(s"BRCAness Wert '${opt.get.value}' nicht im Referenz-Bereich $brcanessRange")
                    at Location("Somatischer NGS-Befund",id,"BRCAness")
                )
              },
             
        msi shouldBe defined otherwise (Info("Fehlende Angabe: MSI Wert") at Location("Somatischer NGS-Befund",id,"MSI"))
          andThen(
            opt =>
              opt.get.value must be (in (msiRange)) otherwise (
                Error(s"MSI Wert '${opt.get.value}' nicht im Referenz-Bereich $msiRange") at Location("Somatischer NGS-Befund",id,"MSI")
              )
            ),
             
        tmb.value must be (in (tmbRange))
          otherwise (Error(s"TMB Wert '${tmb.value}' nicht im Referenz-Bereich $tmbRange") at Location("Somatischer NGS-Befund",id,"TMB")),

        optSnvs.fold(
          List.empty[SimpleVariant].validNel[Issue]
        )(
          _ validateEach (
              snv => 
                (snv.gene.code must be (validGeneSymbol(Location("Somatischer NGS-Befund",id,s"Einfache Variante ${snv.id.value}"))))
                  .map(_ => snv)
            )
        )

        //TODO: validate other variants, at least gene symbols
      )
      .mapN { case _: Product => ngs }

    }

  }



  implicit def carePlanValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    recommendationRefs: Seq[TherapyRecommendation.Id],
    counsellingRequestRefs: Seq[GeneticCounsellingRequest.Id],
    rebiopsyRequestRefs: Seq[RebiopsyRequest.Id],
    studyInclusionRequestRefs: Seq[StudyInclusionRequest.Id]
  ): DataQualityValidator[CarePlan] = {

    case cp @ CarePlan(CarePlan.Id(id),patient,diag,date,_,noTarget,recommendations,counsellingReq,rebiopsyRequests,studyInclusionReq) =>

      (
        patient must be (validReference[Patient.Id](Location("MTB-Beschluss",id,"Patient"))),

        diag must be (validReference(diagnosisRefs)(Location("MTB-Beschluss",id,"Diagnose"))),

        date shouldBe defined otherwise (Warning("Fehlende Angabe: Datum Tumor-Konferenz") at Location("MTB-Beschluss",id,"Datum")),

        // Check that Recommendations are defined unless "noTarget" is declared
/*        
        recommendations mustBe defined otherwise (
          Error("Missing Therapy Recommendations") at Location("MTB-Beschluss",id,"recommendations")
        ) andThen (
          _.get must be (validReferences[TherapyRecommendation.Id](Location("MTB-Beschluss",id,"recommendations")))
        ) andThen (
          _ => noTarget mustBe undefined otherwise (
            Error("'No target' declared despite TherapyRecommendations being defined") at Location("MTB-Beschluss",id,"recommendations")
          )
        ),
*/

        noTarget couldBe defined andThen (
          nt => recommendations.getOrElse(List.empty[TherapyRecommendation.Id]) mustBe empty
        ) otherwise (
          Error("Therapie-Empfehlungen vorhanden obwohl 'Kein Target' deklariert") at Location("MTB-Beschluss",id,"Therapie-Empfehlungen") 
        ) orElse (
          recommendations mustBe defined otherwise (
            Error("Fehlende Angabe: Therapie-Empfehlungen") at Location("MTB-Beschluss",id,"Therapie-Empfehlungen")
          ) andThen (
             _.get must be (validReferences[TherapyRecommendation.Id](Location("MTB-Beschluss",id,"Therapie-Empfehlungen")))
          )
        ),

/*
        // Check that Recommendations are defined or "noTarget" is declared
        recommendations mustBe defined otherwise (
          Error("Missing Therapy Recommendations") at Location("MTB-Beschluss",id,"recommendations"))
          andThen (
            _.get must be (validReferences[TherapyRecommendation.Id](Location("MTB-Beschluss",id,"recommendations")))
          ) orElse (
            noTarget mustBe defined otherwise (
              Error("Missing 'no target' declaration despite empty recommendation list") at Location("MTB-Beschluss",id,"recommendations")
            )
          ),
*/

        counsellingReq
          .map(_ must be (validReference(counsellingRequestRefs)(Location("MTB-Beschluss",id,"Human-genetische Beratungsempfehlung"))))
          .getOrElse(None.validNel[Issue]),
 
        rebiopsyRequests
          .map(_ must be (validReferences[RebiopsyRequest.Id](Location("MTB-Beschluss",id,"Re-Biopsie-Empfehlungen"))))
          .getOrElse(List.empty[RebiopsyRequest.Id].validNel[Issue]),

        studyInclusionReq
          .map(_ must be (validReference(studyInclusionRequestRefs)(Location("MTB-Beschluss",id,"Studien-Einschluss-Empfehlung"))))
          .getOrElse(None.validNel[Issue]),
 
      )
      .mapN { case _: Product => cp }

  }

 
  implicit def recommendationValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    ngsReports: List[SomaticNGSReport]
  ): DataQualityValidator[TherapyRecommendation] = {

    case rec @ TherapyRecommendation(TherapyRecommendation.Id(id),patient,diag,date,medication,priority,loe,optNgsId,supportingVariants) =>

      (
        (patient must be (validReference[Patient.Id](Location("Therapie-Empfehlung",id,"Patient")))),

        diag must be (validReference(diagnosisRefs)(Location("Therapie-Empfehlung",id,"Diagnose"))),

        date shouldBe defined otherwise (
          Warning("Fehlende Angabe: Datum") at Location("Therapie-Empfehlung",id,"Datum")
        ),

        medication.validateEach
          .leftMap(_.map(_.copy(location = Location("Therapie-Empfehlung",id,"Medication")))),

        priority shouldBe defined otherwise (
          Warning("Fehlende Angabe: Priorität") at Location("Therapie-Empfehlung",id,"Priorität")
        ),

        loe shouldBe defined otherwise (
          Warning("Fehlende Angabe: Level of Evidence") at Location("Therapie-Empfehlung",id,"Level of Evidence")
        ),

        optNgsId shouldBe defined otherwise (
          Warning(s"Fehlende Angabe: Referenz auf NGS-Befund") at Location("Therapie-Empfehlung",id,"NGS-Befund")
        ) andThen (
          ngsId =>
            ngsReports.find(_.id == ngsId.get) mustBe defined otherwise (
              Fatal(s"Ungültige Referenz auf NGS-Befund ${ngsId.get.value}") at Location("Therapie-Empfehlung",id,"NGS-Befund")
            ) andThen (
              ngsReport =>

              supportingVariants shouldBe defined otherwise (
                Warning("Fehlende Angabe: Stützende Variante(n)") at Location("Therapie-Empfehlung",id,"Stützende Variante(n)")
              ) andThen (refs =>
                refs.get must be (validReferences(Location("Therapie-Empfehlung",id,"Stützende Variante(n)"))(ngsReport.get.variants.map(_.id))) 
              )
            )
        )
      )
      .mapN { case _: Product => rec }

  }


  implicit def counsellingRequestValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[GeneticCounsellingRequest] = {

    case req @ GeneticCounsellingRequest(GeneticCounsellingRequest.Id(id),patient,date,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Human-genetische Beratungsempfehlung",id,"patient"))),

        date shouldBe defined otherwise (
          Warning("Fehlende Angabe: Datum") at Location("Human-genetische Beratungsempfehlung",id,"Datum")
        ),
      )
      .mapN { case _: Product => req }

  }


  implicit def rebiopsyRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[RebiopsyRequest] = {

    case req @ RebiopsyRequest(RebiopsyRequest.Id(id),patient,specimen,date) =>

      (
        patient must be (validReference[Patient.Id](Location("Re-Biopsie-Empfehlung",id,"Patient"))),

        date shouldBe defined otherwise (Warning("Fehlende Angabe: Recording Date") at Location("Re-Biopsie-Empfehlung",id,"Datum")),

        specimen must be (validReference(specimens)(Location("Re-Biopsie-Empfehlung",id,"Probe"))),
      )
      .mapN { case _: Product => req }

  }


  implicit def histologyReevaluationRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReevaluationRequest] = {

    case req @ HistologyReevaluationRequest(HistologyReevaluationRequest.Id(id),patient,specimen,date) =>

      (
        patient must be (validReference[Patient.Id](Location("Histologie-Reevaluations-Empfehlung",id,"Patient"))),

        date shouldBe defined otherwise (
          Warning("Fehlende Angabe: Datum") at Location("Histologie-Reevaluations-Empfehlung",id,"Datum")
        ),

        specimen must be (validReference(specimens)(Location("Histologie-Reevaluations-Empfehlung",id,"Probe"))),
      )
      .mapN { case _: Product => req }

  }



  private val nctNumRegex = """(NCT\d{8})""".r

  implicit def studyInclusionRequestValidator(
    implicit patId: Patient.Id,
  ): DataQualityValidator[StudyInclusionRequest] = {

    case req @ StudyInclusionRequest(StudyInclusionRequest.Id(id),patient,diag,NCTNumber(nct),date) =>

      (
        patient must be (validReference[Patient.Id](Location("Studien-Einschluss-Empfehlung",id,"Patient"))),

        nct must matchRegex (nctNumRegex) otherwise (
          Error(s"Ungültige NCT-Number '${nct}'") at Location("Studien-Einschluss-Empfehlung",id,"NCT-Nummer")
        ),  

        date shouldBe defined otherwise (Warning("Fehlende Angabe: Datum") at Location("Studien-Einschluss-Empfehlung",id,"Datum")),

      )
      .mapN { case _: Product => req }

  }



  implicit def claimValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id],
  ): DataQualityValidator[Claim] = {

    case cl @ Claim(Claim.Id(id),patient,_,therapy) =>

      (
        patient must be (validReference[Patient.Id](Location("Kostenübernahmeantrag",id,"Patient"))),

        therapy must be (validReference(recommendationRefs)(Location("Kostenübernahmeantrag",id,"Therapie-Empfehlung"))),
      )
      .mapN { case _: Product => cl }

  }


  implicit def claimResponseValidator(
    implicit
    patId: Patient.Id,
    claimRefs: Seq[Claim.Id],
  ): DataQualityValidator[ClaimResponse] = {

    case cl @ ClaimResponse(ClaimResponse.Id(id),claim,patient,_,status,reason) =>

      (
        patient must be (validReference[Patient.Id](Location("Antwort Kostenübernahmeantrag",id,"Patient"))),

        claim must be (validReference(claimRefs)(Location("Antwort Kostenübernahmeantrag",id,"Kostenübernahmeantrag"))),

        if (status == ClaimResponse.Status.Rejected)
          reason shouldBe defined otherwise (
            Warning("Fehlende Angabe: Grund für Ablehnung der Kostenübernahme") at Location("Antwort Kostenübernahmeantrag",id,"Grund für Ablehnung")
          )
        else 
          reason.validNel[Issue]
      )
      .mapN { case _: Product => cl }

  }


  implicit def molecularTherapyValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id]
  ): DataQualityValidator[MolecularTherapy] = {

    case th @ NotDoneTherapy(TherapyId(id),patient,recordedOn,basedOn,notDoneReason,note) =>

      (
        patient must be (validReference[Patient.Id](Location("Molekulare Therapie",id,"Petient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molekulare Therapie",id,"Therapie-Empfehlung")))
      )
      .mapN { case _: Product => th }


    case th @ StoppedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molekulare Therapie",id,"Patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molekulare Therapie",id,"Therapie-Empfehlung"))),

        medication.toList.validateEach
          .leftMap(_.map(_.copy(location = Location("Molekulare Therapie",id,"Medication")))),
      )
      .mapN { case _: Product => th }


    case th @ CompletedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molekulare Therapie",id,"Patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molekulare Therapie",id,"Therapie-Empfehlung"))),

        medication.toList.validateEach
          .leftMap(_.map(_.copy(location = Location("Molekulare Therapie",id,"Medication")))),
      )
      .mapN { case _: Product => th }


    case th @ OngoingTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molekulare Therapie",id,"Patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molekulare Therapie",id,"Therapie-Empfehlung"))),

        medication.toList.validateEach
          .leftMap(_.map(_.copy(location = Location("Molekulare Therapie",id,"Medication")))),
      )
      .mapN { case _: Product => th }

  }


  implicit def reponseValidator(
    implicit
    patId: Patient.Id,
    therapyRefs: Seq[TherapyId]
  ): DataQualityValidator[Response] = {

    case resp @ Response(Response.Id(id),patient,therapy,_,_) =>
      (
        (patient must be (validReference[Patient.Id](Location("Response",id,"Patient")))),

        (therapy must be (validReference(therapyRefs)(Location("Response",id,"Therapie"))))
      )
      .mapN{ case _: Product => resp }

  }



  implicit val mtbFileValidator: DataQualityValidator[MTBFile] = {

    case mtbfile @ MTBFile(
      patient,
      consent,
      episode,
      diagnoses,
      familyMemberDiagnoses,
      previousGuidelineTherapies,
      lastGuidelineTherapy,
      ecogStatus,
      specimens,
      molPathoFindings,
      histologyReports,
      ngsReports,
      carePlans,
      recommendations,
      counsellingRequests,
      rebiopsyRequests,
      histologyReevaluationRequests,
      studyInclusionRequests,
      claims,
      claimResponses,
      molecularTherapies,
      responses
    ) =>

    implicit val patId = patient.id  

    consent.status match {

      case Consent.Status.Rejected => {
        (
          patient.validate,

          consent.validate,

          episode.validate,

          diagnoses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"diagnoses")),

          familyMemberDiagnoses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"familyMemberDiagnoses")),

          previousGuidelineTherapies.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"previousGuidelineTherapies")),

          lastGuidelineTherapy mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"lastGuidelineTherapy")),

          ecogStatus.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ecogStatus")),

          specimens.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"specimens")),

          histologyReports.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"histologyReports")),

          ngsReports.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ngsReports")),

          carePlans.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"carePlans")),

          recommendations.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"recommendations")),

          counsellingRequests.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"counsellingRequests")),

          rebiopsyRequests.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"rebiopsyRequests")),

          claims.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claims")),

          claimResponses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claimResponses")),

          molecularTherapies.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"molecularTherapies")),

          responses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"responses")),
        )
        .mapN { case _: Product => mtbfile }
      }



      case Consent.Status.Active => {
        
        implicit val diagnosisRefs =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.id)

        implicit val icd10codes =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.icd10)
            .filter(_.isDefined)
            .map(_.get.code)
  
        implicit val histoRefs =
          histologyReports.getOrElse(List.empty[HistologyReport]).map(_.id)
  
        implicit val specimenRefs =
          specimens.getOrElse(List.empty[Specimen]).map(_.id)
  
        implicit val allNgsReports =
          ngsReports.getOrElse(List.empty[SomaticNGSReport])
  
        implicit val recommendationRefs =
          recommendations.getOrElse(List.empty[TherapyRecommendation]).map(_.id)
  
        implicit val counsellingRequestRefs =
          counsellingRequests.getOrElse(List.empty[GeneticCounsellingRequest]).map(_.id)
  
        implicit val rebiopsyRequestRefs =
          rebiopsyRequests.getOrElse(List.empty[RebiopsyRequest]).map(_.id)
  
        implicit val studyInclusionRequestRefs =
          studyInclusionRequests.getOrElse(List.empty[StudyInclusionRequest]).map(_.id)
  
        implicit val claimRefs =
          claims.getOrElse(List.empty[Claim]).map(_.id)
 
        // Get List of TherapyIds as combined IDs of Previous and Last Guideline Therapies and Molecular Therapies
        implicit val therapyRefs =
          previousGuidelineTherapies.map(_.map(_.id)).getOrElse(List.empty[TherapyId]) ++
          lastGuidelineTherapy.map(_.id) ++
          molecularTherapies.map(_.flatMap(_.history.map(_.id))).getOrElse(List.empty[TherapyId])
  
        (
          patient.validate,
          consent.validate,
          episode.validate,
  
          diagnoses.filterNot(_.isEmpty) mustBe defined otherwise (
            Error("Fehlende Angabe: Diagnosen") at Location("MTBFile",patId.value,"Diagnosen")
          ) andThen (_.get validateEach),
  
          familyMemberDiagnoses
            .map(_ validateEach)
            .getOrElse(List.empty[FamilyMemberDiagnosis].validNel[Issue]),

          previousGuidelineTherapies.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Vorherige Leitlinien-Therapien") at Location("MTBFile",patId.value,"Vorherige Leitlinien-Therapien")
          ) andThen (_.get validateEach),
  
          lastGuidelineTherapy mustBe defined otherwise (
            Warning("Fehlende Angabe: Letzte Leitlinien-Therapie") at Location("MTBFile",patId.value,"Letzte Leitlinien-Therapie")
          ) andThen (_.get validate),
  
          ecogStatus.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: ECOG Performance Status") at Location("MTBFile",patId.value,"ECOG Status")
          ) andThen (_.get validateEach),
  
          specimens.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Tumor-Proben") at Location("MTBFile",patId.value,"Tumor-Proben")
          ) andThen (_.get validateEach),
  
          histologyReports.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Histologie-Befunde") at Location("MTBFile",patId.value,"Histologie-Befunde")
          ) andThen (_.get validateEach),
  
          molPathoFindings.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Molekular-Pathologie-Befunde") at Location("MTBFile",patId.value,"Molekular-Pathologie-Befunde")
          ) andThen (_.get validateEach),
  
          ngsReports.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Somatische NGS-Befunde") at Location("MTBFile",patId.value,"NGS-Befunde")
          ) andThen (_.get validateEach),
  
          carePlans.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: MTB-Beschlüsse") at Location("MTBFile",patId.value,"MTB-Beschlüsse")
          ) andThen (_.get validateEach),
  
          recommendations.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Therapie-Empfehlungen") at Location("MTBFile",patId.value,"Therapie-Empfehlungen")
          ) andThen (_.get validateEach),
  
          counsellingRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[GeneticCounsellingRequest].validNel[Issue]),
  
          rebiopsyRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[RebiopsyRequest].validNel[Issue]),
  
          histologyReevaluationRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[HistologyReevaluationRequest].validNel[Issue]),
  
          studyInclusionRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[StudyInclusionRequest].validNel[Issue]),
  
          claims.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Kostenübernahmeanträge") at Location("MTBFile",patId.value,"Kostenübernahmeanträge")
          ) andThen (_.get validateEach),
  
          claimResponses.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Antworten auf Kostenübernahmeanträge") at Location("MTBFile",patId.value,"Antworten auf Kostenübernahmeanträge")
          ) andThen (_.get validateEach),
  
          molecularTherapies.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Fehlende Angabe: Molekular-Therapien") at Location("MTBFile",patId.value,"Molekular-Therapien")
          ) andThen (_.get.flatMap(_.history) validateEach),
  
          responses.filterNot(_.isEmpty) mustBe defined otherwise (
            Warning("Fehlende Angabe: Response Befunde") at Location("MTBFile",patId.value,"Response Befunde")
          ) andThen (_.get validateEach),
         
        )
        .mapN { case _: Product => mtbfile }

      }

    }

  }



/*
  private def validReference[Ref](
    location: => Location,
  )(
    implicit ref: Ref
  ): DataQualityValidator[Ref] = {
    r =>
      r must equal (ref) otherwise (
        Fatal(s"Invalid Reference to $r") at location
      )
  }

  private def validReference[Ref, C[X] <: Iterable[X]](
    refs: C[Ref]
  )(
    location: => Location,
  ): DataQualityValidator[Ref] = {
    r =>
      r must be (in (refs)) otherwise (
        Fatal(s"Invalid Reference to $r") at location
      )
  }

  private def validReferences[Ref](
    location: => Location,
  )(
    implicit refs: Iterable[Ref]
  ): DataQualityValidator[List[Ref]] = {
    rs =>
      rs validateEach (
        r => r must be (in (refs)) otherwise (
          Fatal(s"Invalid Reference to $r") at location
        )
      )
  }



  implicit val patientValidator: DataQualityValidator[Patient] = {

    case pat @ Patient(Patient.Id(id),_,birthDate,_,insurance,dod) =>

      (
        birthDate mustBe defined otherwise (Error("Missing BirthDate") at Location("Patient",id,"birthdate")),

        insurance shouldBe defined otherwise (Warning("Missing Health Insurance") at Location("Patient",id,"insurance")),

        dod map (
          date => date must be (before (LocalDate.now)) otherwise (
            Error("Invalid Date of death '${date.toISOFormat}' in the future") at Location("Patient",id,"dateOfDeath")
          )
        ) getOrElse (dod.validNel[Issue]),

        (birthDate, dod)
          .mapN(
            (b,d) =>
              d must be (after (b)) otherwise (
                Error("Invalid Date of death before birthDate") at Location("Patient",id,"dateOfDeath")
              )
          )
          .getOrElse(LocalDate.now.validNel[Issue])
      )
      .mapN { case _: Product => pat }
  }


  implicit def consentValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[Consent] = {

    case consent @ Consent(id,patient,_) =>

      patient must be (validReference[Patient.Id](Location("Consent",id.value,"patient"))) map (_ => consent)

  }


  implicit def episodeValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[MTBEpisode] = {
    case episode @ MTBEpisode(id,patient,period) =>

      patient must be (validReference[Patient.Id](Location("MTB-Episode",id.value,"patient"))) map (_ => episode)

  }


  implicit lazy val icd10gmCatalog = ICD10GMCatalogs.getInstance.get

  implicit lazy val icdO3Catalog   = ICDO3Catalogs.getInstance.get


  implicit def icd10Validator(
    implicit
    catalog: ICD10GMCatalogs
  ): DataQualityValidator[Coding[ICD10GM]] = {

      case icd10 @ Coding(dtos.ICD10GM(code),_,version) =>

        version mustBe defined otherwise (
          Error("Missing ICD-10-GM Version") at Location("ICD-10-GM Coding","","version")
        ) andThen (
          v =>
            attempt(icd.ICD10GM.Version(v.get)) otherwise (
              Error(s"Invalid ICD-10-GM Version '${v.get}'") at Location("ICD-10-GM Coding","","version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.codings(v).map(_.code.value)))
              otherwise (Error(s"Invalid ICD-10-GM code '$code'") at Location("ICD-10-GM Coding","","code"))
        ) map (c => icd10)

    }


  implicit def icdO3TValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3T]] = {

      case icdo3t @ Coding(ICDO3T(code),_,version) =>

        version mustBe defined otherwise (
          Error("Missing ICD-O-3 Version") at Location("ICD-O-3-T Coding","","version")
        ) andThen (
          v => 
            attempt(icd.ICDO3.Version(v.get)) otherwise (
              Error(s"Invalid ICD-O-3 Version '${v.get}'") at Location("ICD-O-3-T Coding","","version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.topographyCodings(v).map(_.code.value)))
              otherwise (Error(s"Invalid ICD-O-3-T code '$code'") at Location("ICD-O-3-T Coding","","code"))
        ) map (c => icdo3t)

    }


  implicit def icdO3MValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3M]] = {

      case icdo3m @ Coding(ICDO3M(code),_,version) =>

        version mustBe defined otherwise (
          Error("Missing ICD-O-3 Version") at Location("ICD-O-3-M Coding","","version")
        ) andThen (
          v =>
            attempt(icd.ICDO3.Version(v.get)) otherwise (
              Error(s"Invalid ICD-O-3 Version '${v.get}'") at Location("ICD-O-3-M Coding","","version")
            )
        ) andThen (
          v =>
            code must be (in (catalog.morphologyCodings(v).map(_.code.value)))
              otherwise (Error(s"Invalid ICD-O-3-M code '$code'") at Location("ICD-O-3-M Coding","","code"))
        ) map (c => icdo3m)

    }


  implicit val medicationCatalog = MedicationCatalog.getInstance.get

  implicit def medicationValidator(
    implicit
    catalog: MedicationCatalog
  ): DataQualityValidator[Coding[Medication]] = {

    case medication @ Coding(Medication(atcCode),_,_) =>

      atcCode must be (in (catalog.entries.map(_.code.value))) otherwise (
        Error(s"Invalid ATC Medication code '$atcCode'") at Location("Medication Coding","","code")
      ) map (c => medication)

  }



  implicit def diagnosisValidator(
    implicit
    patId: Patient.Id,
    specimenRefs: List[Specimen.Id],
    histologyRefs: List[HistologyReport.Id]
  ): DataQualityValidator[Diagnosis] = {

    case diag @ Diagnosis(Diagnosis.Id(id),patient,date,icd10,icdO3T,_,histologyReportRefs,_,glTreatmentStatus) =>

      implicit val diagId = diag.id

      (
        patient must be (validReference[Patient.Id](Location("Diagnosis",id,"patient"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Diagnosis",id,"recordedOn")),

        icd10 mustBe defined otherwise (Error("Missing ICD-10-GM Coding") at Location("Diagnosis",id,"icd10"))
          andThen (_.get validate),

        icdO3T couldBe defined otherwise (Info("Missing ICD-O-3-T Coding") at Location("Diagnosis",id,"icdO3T"))
          andThen (_.get validate),

        histologyReportRefs
          .map(_ must be (validReferences[HistologyReport.Id](Location("Diagnosis",id,"histologyReports"))))
          .getOrElse(List.empty[HistologyReport.Id].validNel[Issue]), 

        glTreatmentStatus mustBe defined otherwise (
          Warning("Missing Guideline Therapy Treatment Status") at Location("Diagnosis",id,"guidelineTreatmentStatus")
        )

      )
      .mapN { case _: Product => diag }

  }


  implicit def famMemberDiagnosisValidator(
    implicit
    patId: Patient.Id,
  ): DataQualityValidator[FamilyMemberDiagnosis] = {

    diag =>
      diag.patient must be (validReference[Patient.Id](Location("Family Member Diagnosis",diag.id.value,"patient"))) map (ref => diag)
  }


//  implicit val therapyLines = (0 to 9).map(TherapyLine(_))
  
  implicit def prevGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
//    therapyLines: Seq[TherapyLine]
  ): DataQualityValidator[PreviousGuidelineTherapy] = {

    case th @ PreviousGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,medication) =>
      (
        patient must be (validReference[Patient.Id](Location("Guideline Therapy",id,"patient"))),

        diag must be (validReference(diagnosisRefs)(Location("Guideline Therapy",id,"diagnosis"))),

        therapyLine shouldBe defined otherwise (
          Warning("Missing Therapy Line") at Location("Guideline Therapy",id,"therapyLine")
//        ) andThen ( l =>
//          l.get must be (in (therapyLines)) otherwise (
//            Error(s"Invalid Therapy Line '${l.get.value}'") at Location("Guideline Therapy",id,"therapyLine")
//          )
        ),

        medication.toList.validateEach
        
      )
      .mapN { case _: Product => th }
  }


  implicit def lastGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
//    therapyLines: Seq[TherapyLine],
    therapyRefs: Seq[TherapyId],
  ): DataQualityValidator[LastGuidelineTherapy] = {

    case th @ LastGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,period,medication,reasonStopped) =>
      (
        patient must be (validReference[Patient.Id](Location("Guideline Therapy",id,"patient"))),

        diag must be (validReference(diagnosisRefs)(Location("Guideline Therapy",id,"diagnosis"))),

        period shouldBe defined otherwise (
          Warning("Missing Therapy Period (Start/End)") at Location("Guideline Therapy",id,"period")
        ) andThen (
          p => p.get.end shouldBe defined otherwise (Warning("Missing Therapy end date") at Location("Guideline Therapy",id,"period"))
        ),

        therapyLine shouldBe defined otherwise (
          Warning("Missing Therapy Line") at Location("Guideline Therapy",id,"therapyLine")
//        ) andThen ( l =>
//          l.get must be (in (therapyLines)) otherwise (
//            Error(s"Invalid Therapy Line '${l.get.value}'") at Location("Guideline Therapy",id,"therapyLine")
//          )
        ),
        
        medication.toList.validateEach,

        reasonStopped shouldBe defined otherwise (
          Warning("Missing Stop Reason") at Location("Guideline Therapy",id,"reasonStopped")
        ),

        th.id must be (in (therapyRefs)) otherwise (
          Warning("Missing Response") at Location("Guideline Therapy",id,"response")
        )
      )
      .mapN { case _: Product => th }

  }


  implicit def ecogStatusValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[ECOGStatus] = {

    case pfSt @ ECOGStatus(id,patient,date,value) =>

      (
        patient must be (validReference[Patient.Id](Location("ECOG Status",id.value,"patient"))),

        date mustBe defined otherwise (
          Error("Missing effective date of ECOG Performance Status finding") at Location("ECOG Status",id.value,"effectiveDate")
        ),
      )
      .mapN { case _: Product => pfSt }

  }


  implicit def specimenValidator(
    implicit
    patId: Patient.Id,
    icd10codes: Seq[ICD10GM]
  ): DataQualityValidator[Specimen] = {

    case sp @ Specimen(Specimen.Id(id),patient,icd10,typ,collection) =>
      (
        patient must be (validReference[Patient.Id](Location("Specimen",id,"patient"))),

        icd10.validate
          andThen (
            icd =>
              icd.code must be (in (icd10codes)) otherwise (
                Fatal(s"Invalid Reference to Diagnosis $icd") at Location("Specimen",id,"icd10")
              )
          ),
  
        typ shouldBe defined otherwise (
          Warning(s"Missing Specimen type") at Location("Specimen",id,"type")
        ),

        collection shouldBe defined otherwise (
          Warning(s"Missing Specimen collection") at Location("Specimen",id,"collection")
        )
       
      )
      .mapN { case _: Product => sp }

  }



  import scala.math.Ordering.Double.TotalOrdering

  private val tcRange = ClosedInterval(0.0 -> 1.0)

  implicit def tumorContentValidator(
    implicit specimens: Seq[Specimen.Id]
  ): DataQualityValidator[TumorCellContent] = {

    case tc @ TumorCellContent(TumorCellContent.Id(id),specimen,method,value) => {

      (
        value must be (in (tcRange)) otherwise (
          Error(s"Tumor content value '$value' (${value*100} %) not in reference range $tcRange")
            at Location("TumorContent",id,"value")
        ) map (_ => tc),

        specimen must be (validReference(specimens)(Location("Tumor Content",id,"specimen")))
      )
      .mapN { case _: Product => tc }
  
    }
     
  }


  implicit def tumorMorphologyValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[TumorMorphology] = {

    case morph @ TumorMorphology(TumorMorphology.Id(id),patient,specimen,icdO3M,notes) =>

      (
        patient must be (validReference[Patient.Id](Location("Tumor Morphology",id,"patient"))),

        specimen must be (validReference(specimens)(Location("Tumor Morphology",id,"specimen"))),
        
        icdO3M.validate
      )
      .mapN {case _: Product => morph }
      
  }


  implicit def histologyReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReport] = {

    case histo @ HistologyReport(HistologyReport.Id(id),patient,specimen,date,morphology,tumorContent) =>

      (
        patient must be (validReference[Patient.Id](Location("Histology Report",id,"patient"))),

        specimen must be (validReference(specimens)(Location("Histology Report",id,"specimen"))),

        date mustBe defined otherwise (Error("Missing issue date") at Location("Histology Report",id,"issuedOn")),

        morphology mustBe defined otherwise (
          Error("Missing Tumor Morphology (ICD-O-3-M) finding") at Location("Histology Report",id,"tumorMorphology")
        ) andThen (_.get validate),

        tumorContent mustBe defined otherwise (
          Error("Missing Tumor Cell Content") at Location("Histology Report",id,"tumorCellContent")
        ) map (_.get) andThen (
          tc =>
            (
              tc.method must equal (TumorCellContent.Method.Histologic)
                otherwise (Error(s"Expected Tumor Cell Content method ${TumorCellContent.Method.Histologic}")
                  at Location("Histology Report",id,"tumorContent")),
    
              tc validate
            )
            .mapN { case _: Product => tc }
          ),
      )
      .mapN { case _: Product => histo }

  }


  implicit def molecularPathologyValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[MolecularPathologyFinding] = {

    case molPath @ MolecularPathologyFinding(MolecularPathologyFinding.Id(id),patient,specimen,_,date,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molecular Pathology Finding",id,"patient"))),

        specimen must be (validReference(specimens)(Location("Molecular Pathology Finding",id,"specimen"))),

        date mustBe defined otherwise (Error("Missing issue date") at Location("Molecular Pathology Finding",id,"issuedOn")),

      )
      .mapN { case _: Product => molPath }

  }


  private val hgncCatalog = HGNCCatalog.getInstance.get

  import scala.language.implicitConversions

  implicit def toHGNCGeneSymbol(gene: Variant.Gene): HGNCGene.Symbol =
    HGNCGene.Symbol(gene.value)

  private def validGeneSymbol(
    location: => Location
  ): DataQualityValidator[Variant.Gene] = 
    symbol => 
      hgncCatalog.geneWithSymbol(symbol) mustBe defined otherwise (
        Error(s"Invalid Gene Symbol ${symbol.value}") at location
      ) map (_ => symbol)
  


  implicit def ngsReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id],
  ): DataQualityValidator[SomaticNGSReport] = {


    case ngs @
      SomaticNGSReport(SomaticNGSReport.Id(id),patient,specimen,date,_,_,tumorContent,brcaness,msi,tmb,optSnvs,_,_,_,_) => {

      import SomaticNGSReport._

      val brcanessRange = ClosedInterval(0.0 -> 1.0)
      val msiRange      = ClosedInterval(0.0 -> 2.0)
      val tmbRange      = ClosedInterval(0.0 -> 1e6)  // TMB in mut/MBase, so [0,1000000]

      (
        patient must be (validReference[Patient.Id](Location("Somatic NGS-Report",id,"patient"))),

        specimen must be (validReference(specimens)(Location("Somatic NGS-Report",id,"specimen"))),

        tumorContent.method must equal (TumorCellContent.Method.Bioinformatic)
          otherwise (Error(s"Expected Tumor Cell Content method '${TumorCellContent.Method.Bioinformatic}'")
            at Location("Somatic NGS-Report",id,"tumorContent")),

        tumorContent validate,
       
        brcaness shouldBe defined otherwise (Info("Missing BRCAness value") at Location("Somatic NGS-Report",id,"brcaness"))
          andThen {
            opt =>
              opt.get.value must be (in (brcanessRange)) otherwise (
                  Error(s"BRCAness value '${opt.get.value}' not in reference range $brcanessRange")
                    at Location("Somatic NGS-Report",id,"brcaness")
                )
              },
             
        msi shouldBe defined otherwise (Info("Missing MSI value") at Location("Somatic NGS-Report",id,"msi"))
          andThen(
            opt =>
              opt.get.value must be (in (msiRange)) otherwise (
                Error(s"MSI value '${opt.get.value}' not in reference range $msiRange") at Location("Somatic NGS-Report",id,"msi")
              )
            ),
             
        tmb.value must be (in (tmbRange))
          otherwise (Error(s"TMB value '${tmb.value}' not in reference range $tmbRange") at Location("Somatic NGS-Report",id,"tmb")),             

        optSnvs.fold(
          List.empty[SimpleVariant].validNel[Issue]
        )(
          _ validateEach (
              snv => 
                (snv.gene.code must be (validGeneSymbol(Location("Somatic NGS-Report",id,s"SimpleVariant/${snv.id.value}"))))
                  .map(_ => snv)
            )
        )

        //TODO: validate other variants, at least gene symbols
      )
      .mapN { case _: Product => ngs }

    }

  }



  implicit def carePlanValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    recommendationRefs: Seq[TherapyRecommendation.Id],
    counsellingRequestRefs: Seq[GeneticCounsellingRequest.Id],
    rebiopsyRequestRefs: Seq[RebiopsyRequest.Id],
    studyInclusionRequestRefs: Seq[StudyInclusionRequest.Id]
  ): DataQualityValidator[CarePlan] = {

    case cp @ CarePlan(CarePlan.Id(id),patient,diag,date,_,noTarget,recommendations,counsellingReq,rebiopsyRequests,studyInclusionReq) =>

      (
        patient must be (validReference[Patient.Id](Location("CarePlan",id,"patient"))),

        diag must be (validReference(diagnosisRefs)(Location("CarePlan",id,"diagnosis"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("CarePlan",id,"issuedOn")),

        // Check that Recommendations are defined unless "noTarget" is declared
        noTarget couldBe defined andThen (
          nt => recommendations.getOrElse(List.empty[TherapyRecommendation.Id]) mustBe empty
        ) otherwise (
          Error("Therapy Recommendations defined despite 'no target' being declared") at Location("CarePlan",id,"recommendations") 
        ) orElse (
          recommendations mustBe defined otherwise (
            Error("Missing Therapy Recommendations") at Location("CarePlan",id,"recommendations")
          ) andThen (
             _.get must be (validReferences[TherapyRecommendation.Id](Location("CarePlan",id,"recommendations")))
          )
        ),

        counsellingReq
          .map(_ must be (validReference(counsellingRequestRefs)(Location("CarePlan",id,"geneticCounsellingRequest"))))
          .getOrElse(None.validNel[Issue]),
 
        rebiopsyRequests
          .map(_ must be (validReferences[RebiopsyRequest.Id](Location("CarePlan",id,"rebiopsyRequests"))))
          .getOrElse(List.empty[RebiopsyRequest.Id].validNel[Issue]),

        studyInclusionReq
          .map(_ must be (validReference(studyInclusionRequestRefs)(Location("CarePlan",id,"studyInclusionRequest"))))
          .getOrElse(None.validNel[Issue]),
 
      )
      .mapN { case _: Product => cp }

  }

 
  implicit def recommendationValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    ngsReports: List[SomaticNGSReport]
  ): DataQualityValidator[TherapyRecommendation] = {

    case rec @ TherapyRecommendation(TherapyRecommendation.Id(id),patient,diag,date,medication,priority,loe,optNgsId,supportingVariants) =>

      (
        (patient must be (validReference[Patient.Id](Location("Therapy Recommendation",id,"patient")))),

        diag must be (validReference(diagnosisRefs)(Location("Therapy Recommendation",id,"diagnosis"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Therapy Recommendation",id,"issuedOn")),

        medication validateEach,

        priority shouldBe defined otherwise (Warning("Missing Priority") at Location("Therapy Recommendation",id,"priority")),

        loe shouldBe defined otherwise (Warning("Missing Level of Evidence") at Location("Therapy Recommendation",id,"levelOfEvidence")),

        optNgsId mustBe defined otherwise (
          Error(s"Missing Reference to Somatic NGS-Report") at Location("Therapy Recommendation",id,"ngsReport")
        ) andThen (
          ngsId =>
            ngsReports.find(_.id == ngsId.get) mustBe defined otherwise (
              Fatal(s"Invalid Reference to SomaticNGSReport/${ngsId.get.value}") at Location("Therapy Recommendation",id,"ngsReport")
            ) andThen (
              ngsReport =>

              supportingVariants shouldBe defined otherwise (
                Warning("Missing Supporting Variants") at Location("Therapy Recommendation",id,"supportingVariants")
              ) andThen (refs =>
                refs.get must be (validReferences(Location("Therapy Recommendation",id,"supportingVariants"))(ngsReport.get.variants.map(_.id))) 
              )
            )
        )
      )
      .mapN { case _: Product => rec }

  }


  implicit def counsellingRequestValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[GeneticCounsellingRequest] = {

    case req @ GeneticCounsellingRequest(GeneticCounsellingRequest.Id(id),patient,date,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Genetic Counselling Request",id,"patient"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Genetic Counselling Request",id,"issuedOn")),
      )
      .mapN { case _: Product => req }

  }


  implicit def rebiopsyRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[RebiopsyRequest] = {

    case req @ RebiopsyRequest(RebiopsyRequest.Id(id),patient,specimen,date) =>

      (
        patient must be (validReference[Patient.Id](Location("Rebiopsy Request",id,"patient"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Rebiopsy Request",id,"issuedOn")),

        specimen must be (validReference(specimens)(Location("Rebiopsy Request",id,"specimen"))),
      )
      .mapN { case _: Product => req }

  }


  implicit def histologyReevaluationRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReevaluationRequest] = {

    case req @ HistologyReevaluationRequest(HistologyReevaluationRequest.Id(id),patient,specimen,date) =>

      (
        patient must be (validReference[Patient.Id](Location("Histology Reevaluation Request",id,"patient"))),

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Histology Reevaluation Request",id,"issuedOn")),

        specimen must be (validReference(specimens)(Location("Histology Reevaluation Request",id,"specimen"))),
      )
      .mapN { case _: Product => req }

  }



  private val nctNumRegex = """(NCT\d{8})""".r

  implicit def studyInclusionRequestValidator(
    implicit patId: Patient.Id,
  ): DataQualityValidator[StudyInclusionRequest] = {

    case req @ StudyInclusionRequest(StudyInclusionRequest.Id(id),patient,diag,NCTNumber(nct),date) =>

      (
        patient must be (validReference[Patient.Id](Location("Study Inclusion Request",id,"patient"))),

        nct must matchRegex (nctNumRegex) otherwise (
          Error(s"Invalid NCT Number pattern '${nct}'") at Location("Study Inclusion Request",id,"nctNumber")),  

        date shouldBe defined otherwise (Warning("Missing Recording Date") at Location("Study Inclusion Request",id,"issuedOn")),

      )
      .mapN { case _: Product => req }

  }



  implicit def claimValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id],
  ): DataQualityValidator[Claim] = {

    case cl @ Claim(Claim.Id(id),patient,_,therapy) =>

      (
        patient must be (validReference[Patient.Id](Location("Claim",id,"patient"))),

        therapy must be (validReference(recommendationRefs)(Location("Claim",id,"specimen"))),
      )
      .mapN { case _: Product => cl }

  }


  implicit def claimResponseValidator(
    implicit
    patId: Patient.Id,
    claimRefs: Seq[Claim.Id],
  ): DataQualityValidator[ClaimResponse] = {

    case cl @ ClaimResponse(ClaimResponse.Id(id),claim,patient,_,status,reason) =>

      (
        patient must be (validReference[Patient.Id](Location("Claim Response",id,"patient"))),

        claim must be (validReference(claimRefs)(Location("Claim Response",id,"claim"))),

        if (status == ClaimResponse.Status.Rejected)
          reason shouldBe defined otherwise (
            Warning("Missing Reason for rejected Claim Response") at Location("Claim Response",id,"reason")
          )
        else 
          reason.validNel[Issue]
      )
      .mapN { case _: Product => cl }

  }


  implicit def molecularTherapyValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id]
  ): DataQualityValidator[MolecularTherapy] = {

    case th @ NotDoneTherapy(TherapyId(id),patient,recordedOn,basedOn,notDoneReason,note) =>

      (
        patient must be (validReference[Patient.Id](Location("Molecular Therapy",id,"patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molecular Therapy",id,"basedOn")))
      )
      .mapN { case _: Product => th }


    case th @ StoppedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molecular Therapy",id,"patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molecular Therapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }


    case th @ CompletedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molecular Therapy",id,"patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molecular Therapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }


    case th @ OngoingTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        patient must be (validReference[Patient.Id](Location("Molecular Therapy",id,"patient"))),

        basedOn must be (validReference(recommendationRefs)(Location("Molecular Therapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }

  }


  implicit def reponseValidator(
    implicit
    patId: Patient.Id,
    therapyRefs: Seq[TherapyId]
  ): DataQualityValidator[Response] = {

    case resp @ Response(Response.Id(id),patient,therapy,_,_) =>
      (
        (patient must be (validReference[Patient.Id](Location("Response",id,"patient")))),

        (therapy must be (validReference(therapyRefs)(Location("Response",id,"therapy"))))
      )
      .mapN{ case _: Product => resp }

  }



  implicit val mtbFileValidator: DataQualityValidator[MTBFile] = {

    case mtbfile @ MTBFile(
      patient,
      consent,
      episode,
      diagnoses,
      familyMemberDiagnoses,
      previousGuidelineTherapies,
      lastGuidelineTherapy,
      ecogStatus,
      specimens,
      molPathoFindings,
      histologyReports,
      ngsReports,
      carePlans,
      recommendations,
      counsellingRequests,
      rebiopsyRequests,
      histologyReevaluationRequests,
      studyInclusionRequests,
      claims,
      claimResponses,
      molecularTherapies,
      responses
    ) =>

    implicit val patId = patient.id  

    consent.status match {

      case Consent.Status.Rejected => {
        (
          patient.validate,

          consent.validate,

          episode.validate,

          diagnoses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"diagnoses")),

          familyMemberDiagnoses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"familyMemberDiagnoses")),

          previousGuidelineTherapies.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"previousGuidelineTherapies")),

          lastGuidelineTherapy mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"lastGuidelineTherapy")),

          ecogStatus.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ecogStatus")),

          specimens.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"specimens")),

          histologyReports.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"histologyReports")),

          ngsReports.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ngsReports")),

          carePlans.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"carePlans")),

          recommendations.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"recommendations")),

          counsellingRequests.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"counsellingRequests")),

          rebiopsyRequests.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"rebiopsyRequests")),

          claims.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claims")),

          claimResponses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claimResponses")),

          molecularTherapies.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"molecularTherapies")),

          responses.filter(_.isEmpty) mustBe undefined otherwise (
            Fatal(s"MDAT must not be present for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"responses")),
        )
        .mapN { case _: Product => mtbfile }
      }



      case Consent.Status.Active => {
        
        implicit val diagnosisRefs =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.id)

        implicit val icd10codes =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.icd10)
            .filter(_.isDefined)
            .map(_.get.code)
  
        implicit val histoRefs =
          histologyReports.getOrElse(List.empty[HistologyReport]).map(_.id)
  
        implicit val specimenRefs =
          specimens.getOrElse(List.empty[Specimen]).map(_.id)
  
        implicit val allNgsReports =
          ngsReports.getOrElse(List.empty[SomaticNGSReport])
  
        implicit val recommendationRefs =
          recommendations.getOrElse(List.empty[TherapyRecommendation]).map(_.id)
  
        implicit val counsellingRequestRefs =
          counsellingRequests.getOrElse(List.empty[GeneticCounsellingRequest]).map(_.id)
  
        implicit val rebiopsyRequestRefs =
          rebiopsyRequests.getOrElse(List.empty[RebiopsyRequest]).map(_.id)
  
        implicit val studyInclusionRequestRefs =
          studyInclusionRequests.getOrElse(List.empty[StudyInclusionRequest]).map(_.id)
  
        implicit val claimRefs =
          claims.getOrElse(List.empty[Claim]).map(_.id)
 
        // Get List of TherapyIds as combined IDs of Previous and Last Guideline Therapies and Molecular Therapies
        implicit val therapyRefs =
          previousGuidelineTherapies.map(_.map(_.id)).getOrElse(List.empty[TherapyId]) ++
          lastGuidelineTherapy.map(_.id) ++
          molecularTherapies.map(_.flatMap(_.history.map(_.id))).getOrElse(List.empty[TherapyId])
  
        (
          patient.validate,
          consent.validate,
          episode.validate,
  
          diagnoses.filterNot(_.isEmpty) mustBe defined otherwise (
            Error("Missing diagnosis records") at Location("MTBFile",patId.value,"diagnoses")
          ) andThen (_.get validateEach),
  
          familyMemberDiagnoses
            .map(_ validateEach)
            .getOrElse(List.empty[FamilyMemberDiagnosis].validNel[Issue]),

          previousGuidelineTherapies.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing previous Guideline Therapies") at Location("MTBFile",patId.value,"previousGuidelineTherapies")
          ) andThen (_.get validateEach),
  
          lastGuidelineTherapy mustBe defined otherwise (
            Warning("Missing last Guideline Therapy") at Location("MTBFile",patId.value,"lastGuidelineTherapies")
          ) andThen (_.get validate),
  
          ecogStatus.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing ECOG Performance Status records") at Location("MTBFile",patId.value,"ecogStatus")
          ) andThen (_.get validateEach),
  
          specimens.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing Specimen records") at Location("MTBFile",patId.value,"specimens")
          ) andThen (_.get validateEach),
  
          histologyReports.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing HistologyReport records") at Location("MTBFile",patId.value,"histologyReports")
          ) andThen (_.get validateEach),
  
          molPathoFindings.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing MolecularPathology records") at Location("MTBFile",patId.value,"molecularPathologyFindings")
          ) andThen (_.get validateEach),
  
          ngsReports.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing SomaticNGSReport records") at Location("MTBFile",patId.value,"ngsReports")
          ) andThen (_.get validateEach),
  
          carePlans.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing CarePlan records") at Location("MTBFile",patId.value,"carePlans")
          ) andThen (_.get validateEach),
  
          recommendations.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing TherapyRecommendation records") at Location("MTBFile",patId.value,"recommendations")
          ) andThen (_.get validateEach),
  
          counsellingRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[GeneticCounsellingRequest].validNel[Issue]),
  
          rebiopsyRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[RebiopsyRequest].validNel[Issue]),
  
          histologyReevaluationRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[HistologyReevaluationRequest].validNel[Issue]),
  
          studyInclusionRequests.filterNot(_.isEmpty)
            .map(_ validateEach)
            .getOrElse(List.empty[StudyInclusionRequest].validNel[Issue]),
  
          claims.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing Insurance Claim records") at Location("MTBFile",patId.value,"claims")
          ) andThen (_.get validateEach),
  
          claimResponses.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing ClaimResponse records") at Location("MTBFile",patId.value,"claimResponses")
          ) andThen (_.get validateEach),
  
          molecularTherapies.filterNot(_.isEmpty) shouldBe defined otherwise (
            Warning("Missing MolecularTherapy records") at Location("MTBFile",patId.value,"molecularTherapies")
          ) andThen (_.get.flatMap(_.history) validateEach),
  
          responses.filterNot(_.isEmpty) mustBe defined otherwise (
            Warning("Missing Response records") at Location("MTBFile",patId.value,"responses")
          ) andThen (_.get validateEach),
         
        )
        .mapN { case _: Product => mtbfile }

      }

    }

  }
*/
}
