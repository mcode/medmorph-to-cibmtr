package org.mitre.hapifhir;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import static org.junit.Assert.assertEquals;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.List;
import java.util.ArrayList;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Quantity;
import org.json.JSONArray;
import org.json.JSONObject;

public class MedMorphToCIBMTRTest {
  @Rule
  public WireMockRule wireMockRule = new WireMockRule(4444);

  Bundle medmorphReport;
  MessageHeader messageHeader;
  Patient patient;
  MedMorphToCIBMTR medmorphToCIBMTR;
  String expectedCrid = "1982897480019337";
  String expectedResourceId = "8557319952834071";
  String expectedCcn = "12001";
  String orgId = "test-org";

  @Before
  public void setUp() {
    medmorphReport = new Bundle();

    messageHeader = new MessageHeader();
    messageHeader.setSource(new MessageHeader.MessageSourceComponent()
      .setEndpoint("http://localhost:4444/fhir"));
    messageHeader.setSender(new Reference().setReference("Organization/" + orgId));
    medmorphReport.addEntry().setResource(messageHeader);

    Bundle contentBundle = new Bundle();
    patient = new Patient();
    patient.addName().setFamily("Doe").addGiven("John");
    patient.setGender(AdministrativeGender.MALE);
    patient.setBirthDateElement(new DateType("2000-01-01"));
    contentBundle.addEntry().setResource(patient);

    Observation ob1 = new Observation();
    ob1.setSubject(new Reference("Patient/" + expectedResourceId));
    ob1.setEffective(new DateTimeType("2010-01-01"));
    ob1.getCode().addCoding().setCode("8302-2").setSystem("http://loinc.org").setDisplay("Body Height");
    ob1.setValue(new Quantity());
    ob1.getValueQuantity().setCode("cm");
    ob1.getValueQuantity().setSystem("http://unitsofmeasure.org");
    ob1.getValueQuantity().setUnit("cm");
    ob1.getValueQuantity().setValue(69.8);
    contentBundle.addEntry().setResource(ob1);

    Observation ob2 = new Observation();
    ob2.setSubject(new Reference("Patient/" + expectedResourceId));
    ob2.setEffective(new DateTimeType("2010-01-01"));
    ob2.getCode().addCoding().setCode("29463-7").setSystem("http://loinc.org").setDisplay("Body Weight");
    ob2.setValue(new Quantity());
    ob2.getValueQuantity().setCode("kg");
    ob2.getValueQuantity().setSystem("http://unitsofmeasure.org");
    ob2.getValueQuantity().setUnit("kg");
    ob2.getValueQuantity().setValue(68.2);
    contentBundle.addEntry().setResource(ob2);
    medmorphReport.addEntry().setResource(contentBundle);

    Organization organization = new Organization();
    organization.setId(orgId);
    List<Identifier> ids = new ArrayList<Identifier>();
    Identifier ccnId = new Identifier();
    ccnId.setSystem("http://cibmtr.org/codesystem/transplant-center");
    ccnId.setValue(expectedCcn);
    ids.add(ccnId);
    organization.setIdentifier(ids);
    medmorphReport.addEntry().setResource(organization);

    medmorphToCIBMTR = new MedMorphToCIBMTR("http://localhost:4444/");
  }

  // Uncomment the test below to post bundle to test service hosted on pathways.mitre.org
  // @Test
  // public void convertTest() {
  //   MedMorphToCIBMTR testService = new MedMorphToCIBMTR("http://pathways.mitre.org:4444/");
  //   testService.convert(medmorphReport, messageHeader, "");
  // }

  @Test
  public void getCridTest() {
    stubFor(put(urlMatching("/CRID"))
      .willReturn(aResponse()
        .withBody("{\"perfectMatch\":[{\"matchedCriteria\":[\"firstName\",\"lastName\",\"gender\",\"birthDate\"],\"matchType\":\"Perfect1\",\"crid\":1982897480019337}]}")));

    Number actualCrid = medmorphToCIBMTR.getCrid("", expectedCcn, patient);
    assertEquals(expectedCrid, actualCrid.toString());
  }

  @Test
  public void postPatientTest() {
    stubFor(post(urlMatching("/Patient"))
      .willReturn(aResponse()
        .withHeader("Location", "http://localhost:4444/Patient/" + expectedResourceId)));

    String actualResourceId = medmorphToCIBMTR.postPatient("", expectedCcn, expectedCrid);
    assertEquals(expectedResourceId, actualResourceId);
  }

  @Test
  public void getMetaTest() {
    JSONObject metaObject = medmorphToCIBMTR.getMeta(expectedCcn);
    JSONArray securityArray = metaObject.getJSONArray("security");
    JSONObject securityObject = securityArray.getJSONObject(0);
    assertEquals(securityObject.getString("code"), "rc_" + expectedCcn);
  }

  @Test
  public void getCcnTest() {
    String actualCcn = medmorphToCIBMTR.getCcn(medmorphReport.getEntry(), messageHeader);
    assertEquals(actualCcn, expectedCcn);
  }
}
