package org.mitre.hapifhir;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.ResponseHandler;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.json.JSONArray;
import org.json.JSONObject;

public class MedMorphToCIBMTR {
  private String cibmtrUrl;

  public MedMorphToCIBMTR(String cibmtrUrl) {
    this.cibmtrUrl = cibmtrUrl;
    if (!this.cibmtrUrl.endsWith("/")) this.cibmtrUrl += "/";
  }

  public void convert(Bundle medmorphReport, MessageHeader messageHeader, String authToken) {
    // https://fhir.nmdp.org/ig/cibmtr-reporting/CIBMTR_Direct_FHIR_API_Connection_Guide_STU3.pdf
    if (medmorphReport.hasEntry()) {
      List<BundleEntryComponent> entriesList = medmorphReport.getEntry();
      BundleEntryComponent patientEntry = entriesList.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Patient).findAny().orElse(null);
      String ccn = getCcn(entriesList, messageHeader);
      if (patientEntry == null || ccn == null) return;

      Patient patient = (Patient) patientEntry.getResource();
      Number crid = getCrid(authToken, ccn, patient);
      String resourceId = postPatient(authToken, ccn, crid.toString());

      if (resourceId != null) postBundle(authToken, ccn, entriesList, resourceId);
    }
  }

  // Register patient with CIBMTR and returns CRID
  protected Number getCrid(String authToken, String ccn, Patient patient) {
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPut httpPut = new HttpPut(cibmtrUrl + "CRID");
      httpPut.setHeader("Accept", "application/json");
      httpPut.setHeader("Content-type", "application/json");
      httpPut.setHeader("Authorization", authToken);

      JSONObject cridRequestBody = new JSONObject();
      cridRequestBody.put("ccn", ccn);
      JSONObject patientJson = new JSONObject();
      patientJson.put("firstName", patient.getName().get(0).getGiven().get(0));
      patientJson.put("lastName", patient.getName().get(0).getFamily());
      patientJson.put("birthDate", patient.getBirthDate().toString());
      patientJson.put("gender", patient.getGender().getDisplay());
      cridRequestBody.put("patient", patientJson);

      StringEntity stringEntity = new StringEntity(cridRequestBody.toString());
      httpPut.setEntity(stringEntity);
      ResponseHandler<String> responseHandler = response -> {
        HttpEntity entity = response.getEntity();
        return entity != null ? EntityUtils.toString(entity) : null;
      };

      String responseBody = httpClient.execute(httpPut, responseHandler);
      JSONObject responseObj = new JSONObject(responseBody.toString());
      JSONArray perfectMatch = responseObj.getJSONArray("perfectMatch");
      if (!perfectMatch.isEmpty()) return perfectMatch.getJSONObject(0).getNumber("crid");
    } catch (Exception e) {
      return null;
    }

    return null;
  }

  // POST Patient resource with CRID and return resource id
  protected String postPatient(String authToken, String ccn, String crid) {
    if (crid == null) return null;

    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPost httpPost = new HttpPost(cibmtrUrl + "Patient");
      httpPost.setHeader("Content-Type", "application/fhir+json");
      httpPost.setHeader("Authorization", authToken);

      JSONObject patientRequestBody = new JSONObject();
      patientRequestBody.put("resourceType", "Patient");
      patientRequestBody.put("meta", getMeta(ccn));
      patientRequestBody.put("text", (new JSONObject()).put("status", "empty"));
      JSONArray identifierArray = new JSONArray();
      JSONObject identifierObject = new JSONObject();
      identifierObject.put("use", "official");
      identifierObject.put("system", "http://cibmtr.org/identifier/CRID");
      identifierObject.put("value", crid);
      identifierArray.put(identifierObject);
      patientRequestBody.put("identifier", identifierArray);

      StringEntity stringEntity = new StringEntity(patientRequestBody.toString());
      httpPost.setEntity(stringEntity);
      ResponseHandler<String> responseHandler = response -> {
        int status = response.getStatusLine().getStatusCode();
        if (status == 200) {
          String location = response.getFirstHeader("Location").getValue();
          int index = location.indexOf("Patient/");
          if (index > 0) return location.substring(index + 8);
        }

        return null;
      };

      return httpClient.execute(httpPost, responseHandler);
    } catch (Exception e) {
      return null;
    }
  }

  // Post bundle of observations
  protected void postBundle(String authToken, String ccn, List<BundleEntryComponent> entries, String resourceId) {
    List<BundleEntryComponent> observationEntries = entries.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Observation).collect(Collectors.toList());
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPost httpPost = new HttpPost(cibmtrUrl + "Bundle");
      httpPost.setHeader("Content-Type", "application/fhir+json");
      httpPost.setHeader("Authorization", authToken);

      JSONObject bundleRequestBody = new JSONObject();
      bundleRequestBody.put("resourceType", "Bundle");
      bundleRequestBody.put("type", "transaction");
      bundleRequestBody.put("entry", getObservationEntries(ccn, observationEntries, resourceId));

      StringEntity stringEntity = new StringEntity(bundleRequestBody.toString());
      httpPost.setEntity(stringEntity);
      httpClient.execute(httpPost);
    } catch (Exception e) {
      return;
    }
  }

  protected JSONArray getObservationEntries(String ccn, List<BundleEntryComponent> observationEntries, String resourceId) {
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
      observationResourceObject.put("meta", getMeta(ccn));
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

  protected JSONObject getMeta(String ccn) {
    JSONObject metaObject = new JSONObject();
    JSONArray securityArray = new JSONArray();
    JSONObject securityObject = new JSONObject();
    securityObject.put("system", "http://cibmtr.org/codesystem/transplant-center");
    securityObject.put("code", "rc_" + ccn);
    securityArray.put(securityObject);
    metaObject.put("security", securityArray);

    return metaObject;
  }

  // Extracts CCN from MessageHeader.sender.identifier
  protected String getCcn(List<BundleEntryComponent> bundleEntries, MessageHeader messageHeader) {
    return null;
  }
}
