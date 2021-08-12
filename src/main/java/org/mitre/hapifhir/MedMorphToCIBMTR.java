package org.mitre.hapifhir;

import java.util.List;
import java.util.Map;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Quantity;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class MedMorphToCIBMTR {
  private final String CIBMTR_URL = "http://pathways.mitre.org:4444/";

  public void convert(Bundle medmorphReport) {
    // https://fhir.nmdp.org/ig/cibmtr-reporting/CIBMTR_Direct_FHIR_API_Connection_Guide_STU3.pdf
    if (medmorphReport.hasEntry()) {
      List<BundleEntryComponent> entriesList = medmorphReport.getEntry();
      BundleEntryComponent patientEntry = entriesList.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Patient).findAny().orElse(null);
      if (patientEntry == null) return;

      Patient patient = (Patient) patientEntry.getResource();
      String crid = getCrid(patient);
      String resourceId = postPatient(crid);

      if (resourceId != null) postBundle(entriesList, resourceId);
    }
  }

  // Register patient with CIBMTR and returns CRID
  private String getCrid(Patient patient) {
    try {
      URL cridUrl = new URL(CIBMTR_URL + "CRID");
      HttpURLConnection connection = (HttpURLConnection) cridUrl.openConnection();
      connection.setRequestMethod("PUT");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");

      JSONObject cridRequestBody = new JSONObject();
      cridRequestBody.put("ccn", "12345");
      JSONObject patientJson = new JSONObject();
      patientJson.put("firstName", patient.getName().get(0).getGiven().get(0));
      patientJson.put("lastName", patient.getName().get(0).getFamily());
      patientJson.put("birthdate", patient.getBirthDate().toString());
      patientJson.put("gender", patient.getGender().getDisplay());
      cridRequestBody.put("patient", patientJson);

      OutputStream os = connection.getOutputStream();
      byte[] input = cridRequestBody.toString().getBytes("utf-8");
      os.write(input, 0, input.length);

      BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
      StringBuilder response = new StringBuilder();
      String responseLine = null;
      while ((responseLine = br.readLine()) != null) {
          response.append(responseLine.trim());
      }
      JSONObject responseObj = new JSONObject(response.toString());
      JSONArray perfectMatch = responseObj.getJSONArray("perfectMatch");
      if (!perfectMatch.isEmpty()) return (String)perfectMatch.getJSONObject(0).get("crid");
    } catch (Exception e) {
      return null;
    }

    return null;
  }

  // POST Patient resource with CRID and return resource id
  private String postPatient(String crid) {
    if (crid == null) return null;

    try {
      URL patientUrl = new URL(CIBMTR_URL + "Patient");
      HttpURLConnection patientConnection = (HttpURLConnection) patientUrl.openConnection();
      patientConnection.setRequestMethod("POST");
      patientConnection.setDoOutput(true);
      patientConnection.setRequestProperty("Content-Type", "application/fhir+json");

      JSONObject patientRequestBody = new JSONObject();
      patientRequestBody.put("resourceType", "Patient");
      patientRequestBody.put("meta", getMeta());
      patientRequestBody.put("text", (new JSONObject()).put("status", "empty"));
      JSONArray identifierArray = new JSONArray();
      JSONObject identifierObject = new JSONObject();
      identifierObject.put("use", "official");
      identifierObject.put("system", "http://cibmtr.org/identifier/CRID");
      identifierObject.put("value", crid);
      identifierArray.put(identifierObject);
      patientRequestBody.put("identifier", identifierArray);

      OutputStream os = patientConnection.getOutputStream();
      byte[] input = patientRequestBody.toString().getBytes("utf-8");
      os.write(input, 0, input.length);

      int responseCode = patientConnection.getResponseCode();
      if (responseCode == 200) {
        // Location header response field contains a URL that includes the resource id after 'Patient/'
        Map<String, List<String>> map = patientConnection.getHeaderFields();
        String location = map.get("Location").get(0);
        int index = location.indexOf("Patient/");
        if (index > 0) return location.substring(index + 8);
      }
    } catch (Exception e) {
      return null;
    }
    
    return null;
  }

  // Post bundle of observations
  private void postBundle(List<BundleEntryComponent> entries, String resourceId) {
    List<BundleEntryComponent> observationEntries = entries.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Observation).collect(Collectors.toList());
    try {
      URL bundleUrl = new URL(CIBMTR_URL + "Bundle");
      HttpURLConnection bundleConnection = (HttpURLConnection) bundleUrl.openConnection();
      bundleConnection.setRequestMethod("POST");
      bundleConnection.setDoOutput(true);
      bundleConnection.setRequestProperty("Content-Type", "application/fhir+json");

      JSONObject bundleRequestBody = new JSONObject();
      bundleRequestBody.put("resourceType", "Bundle");
      bundleRequestBody.put("type", "transaction");
      bundleRequestBody.put("entry", getObservationEntries(observationEntries, resourceId));

      OutputStream os = bundleConnection.getOutputStream();
      byte[] input = bundleRequestBody.toString().getBytes("utf-8");
      os.write(input, 0, input.length);
    } catch (Exception e) {
      return;
    }
  }

  private JSONArray getObservationEntries(List<BundleEntryComponent> observationEntries, String resourceId) {
    JSONArray entryArray = new JSONArray();
    
    for (BundleEntryComponent entry : observationEntries) {
      JSONObject observationObject = new JSONObject();
      JSONObject requestObject = new JSONObject();
      requestObject.put("method", "POST");
      requestObject.put("url", "Observation");
      observationObject.put("request", requestObject);

      Observation observation = (Observation)entry.getResource();
      JSONObject observationResourceObject = new JSONObject();
      observationResourceObject.put("resourceType", "Observation");
      observationResourceObject.put("meta", getMeta());
      observationResourceObject.put("subject", (new JSONObject()).put("reference", "Patient/" + resourceId));
      observationResourceObject.put("effectiveDateTime", observation.getEffectiveDateTimeType().dateTimeValue().getValue());
      CodeableConcept code = observation.getCode();
      Coding coding = code.getCoding().get(0);
      JSONObject codingObject = new JSONObject();
      codingObject.put("system", coding.getSystem());
      codingObject.put("code", coding.getCode());
      codingObject.put("display", coding.getDisplay());
      JSONObject codeObject = new JSONObject();
      codeObject.put("coding", (new JSONArray()).put(codingObject));
      observationResourceObject.put("code", codeObject);
      Quantity quantity = observation.getValueQuantity();
      JSONObject quantityObject = new JSONObject();
      quantityObject.put("value", quantity.getValue());
      quantityObject.put("unit", quantity.getUnit());
      quantityObject.put("system", quantity.getSystem());
      quantityObject.put("code", quantity.getCode());
      observationResourceObject.put("valueQuantity", quantityObject);
      observationObject.put("resource", observationResourceObject);
      entryArray.put(observationObject);
    }

    return entryArray;
  }

  private JSONObject getMeta() {
    JSONObject metaObject = new JSONObject();
    JSONArray securityArray = new JSONArray();
    JSONObject securityObject = new JSONObject();
    securityObject.put("system", "http://cibmtr.org/codesystem/transplant-center");
    securityObject.put("code", "rc_12001");
    securityArray.put(securityObject);
    metaObject.put("security", securityArray);

    return metaObject;
  }
}
