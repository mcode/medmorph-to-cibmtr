package org.mitre.hapifhir;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Quantity;

public class MedMorphToCIBMTRTest {

  Bundle medmorphReport;
  Patient patient;
  MedMorphToCIBMTR medmorphToCIBMTR;
  String expectedCrid = "7732993161784245";
  String expectedResourceId = "6900318524467287";

  @Before
  public void setUp() {
    medmorphReport = new Bundle();

    patient = new Patient();
    patient.addName().setFamily("Doe").addGiven("John");
    patient.setGender(AdministrativeGender.MALE);
    patient.setBirthDateElement(new DateType("2000-01-01"));
    medmorphReport.addEntry().setResource(patient);

    Observation ob1 = new Observation();
    ob1.setSubject(new Reference("Patient/" + expectedResourceId));
    ob1.setEffective(new DateTimeType("2010-01-01"));
    ob1.getCode().addCoding().setCode("8302-2").setSystem("http://loinc.org").setDisplay("Body Height");
    ob1.setValue(new Quantity());
    ob1.getValueQuantity().setCode("cm");
    ob1.getValueQuantity().setSystem("http://unitsofmeasure.org");
    ob1.getValueQuantity().setUnit("cm");
    ob1.getValueQuantity().setValue(69.8);
    medmorphReport.addEntry().setResource(ob1);

    Observation ob2 = new Observation();
    ob2.setSubject(new Reference("Patient/" + expectedResourceId));
    ob2.setEffective(new DateTimeType("2010-01-01"));
    ob2.getCode().addCoding().setCode("29463-7").setSystem("http://loinc.org").setDisplay("Body Weight");
    ob2.setValue(new Quantity());
    ob2.getValueQuantity().setCode("kg");
    ob2.getValueQuantity().setSystem("http://unitsofmeasure.org");
    ob2.getValueQuantity().setUnit("kg");
    ob2.getValueQuantity().setValue(68.2);
    medmorphReport.addEntry().setResource(ob2);

    medmorphToCIBMTR = new MedMorphToCIBMTR("http://pathways.mitre.org:4444/", "1234");
  }

  @Test
  public void convertTest() {
    medmorphToCIBMTR.convert(medmorphReport, "");
  }

  @Test
  public void getCridTest() {
    Number actualCrid = medmorphToCIBMTR.getCrid("", patient);
    assertEquals(expectedCrid, actualCrid.toString());
  }

  @Test
  public void postPatientTest() {
    String actualResourceId = medmorphToCIBMTR.postPatient("", expectedCrid);
    assertEquals(expectedResourceId, actualResourceId);
  }
}
