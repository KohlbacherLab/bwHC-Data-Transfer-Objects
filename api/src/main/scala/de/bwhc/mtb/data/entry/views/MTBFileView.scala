package de.bwhc.mtb.data.entry.views


import play.api.libs.json.Json

import de.bwhc.mtb.data.entry.dtos._


final case class MTBFileView
(
  patient: PatientView,
  diagnoses: List[DiagnosisView],
  familyMemberDiagnoses: List[FamilyMemberDiagnosisView],
  guidelineTherapies: List[GuidelineTherapyView],
  ecogStatus: Option[ECOGStatusView],
  specimens: List[SpecimenView],
  molecularPathologyFindings: List[MolecularPathologyFindingView],
  histologyReports: List[HistologyReportView],
  //TODO: NGSReports
  claimStatus: List[ClaimStatusView], 

)


object MTBFileView
{
  implicit val format = Json.writes[MTBFileView]
}
