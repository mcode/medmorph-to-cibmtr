package org.mitre.hapifhir;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
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
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MessageHeader;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.json.JSONArray;
import org.json.JSONObject;

public class MedMorphToCIBMTR {
  private static final String CCN_SYSTEM = "http://cibmtr.org/codesystem/transplant-center";
  private static final String CRID_SYSTEM = "http://cibmtr.org/identifier/CRID";
  private static final String RESOURCE_IDENTIFIER_SYSTEM = "urn:ietf:rfc:3986";
  private String cibmtrUrl;

  public MedMorphToCIBMTR(String cibmtrUrl) {
    this.cibmtrUrl = cibmtrUrl;
    if (!this.cibmtrUrl.endsWith("/")) this.cibmtrUrl += "/";
  }

  public void convert(Bundle medmorphReport, MessageHeader messageHeader, String authToken) {
    // https://fhir.nmdp.org/ig/cibmtr-reporting/CIBMTR_Direct_FHIR_API_Connection_Guide_STU3.pdf
    if (medmorphReport.hasEntry()) {
      List<BundleEntryComponent> reportEntries = medmorphReport.getEntry();
      // Content bundle should be 2nd entry in report bundle
      Bundle contentBundle = (Bundle) reportEntries.get(1).getResource();
      List<BundleEntryComponent> contentEntries = contentBundle.getEntry();
      BundleEntryComponent patientEntry = contentEntries.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Patient).findAny().orElse(null);
      String ccn = getCcn(reportEntries, messageHeader);
      if (patientEntry == null || ccn == null) return;

      Patient patient = (Patient) patientEntry.getResource();
      Number crid = getCrid(authToken, ccn, patient);
      if (crid == null) return;
      String resourceId = checkIfPatientExists(authToken, ccn, crid.toString());
      boolean isPatientNew = false;
      if (resourceId == null) {
        isPatientNew = true;
        resourceId = postPatient(authToken, ccn, crid.toString());
      }

      if (resourceId != null) postBundle(authToken, ccn, contentEntries, resourceId, isPatientNew);
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

  private ResponseHandler<String> getResponseHandler = response -> {
    int status = response.getStatusLine().getStatusCode();
    if (status != 200) return null;
    HttpEntity entity = response.getEntity();
    return entity != null ? EntityUtils.toString(entity) : null;
  };

  protected String checkIfPatientExists(String authToken, String ccn, String crid) {
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      // Check if patient has already been submitted
      HttpGet httpGet = new HttpGet(cibmtrUrl + "Patient?_security=" + CCN_SYSTEM + "%7Crc_" + ccn + "&identifier=" + crid);
      httpGet.setHeader("Content-Type", "application/fhir+json");
      httpGet.setHeader("Authorization", authToken);

      String responseBody = httpClient.execute(httpGet, getResponseHandler);
      if (responseBody != null) {
        JSONObject responseObj = new JSONObject(responseBody.toString());
        if (responseObj.getInt("total") > 0) {
          // Return patient resource id if patient exists
          return responseObj.getJSONArray("entry").getJSONObject(0).getJSONObject("resource").getString("id");
        }
      }

      return null;
    } catch (Exception e) {
      return null;
    }
  }

  // POST Patient resource with CRID and return resource id
  protected String postPatient(String authToken, String ccn, String crid) {
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
      identifierObject.put("system", CRID_SYSTEM);
      identifierObject.put("value", crid);
      identifierArray.put(identifierObject);
      patientRequestBody.put("identifier", identifierArray);

      StringEntity stringEntity = new StringEntity(patientRequestBody.toString());
      httpPost.setEntity(stringEntity);
      ResponseHandler<String> responseHandler = response -> {
        int status = response.getStatusLine().getStatusCode();
        if (status == 200 || status == 201) {
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
  protected void postBundle(String authToken, String ccn, List<BundleEntryComponent> entries, String resourceId, boolean isPatientNew) {
    List<BundleEntryComponent> observationEntries = entries.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Observation).collect(Collectors.toList());
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPost httpPost = new HttpPost(cibmtrUrl + "Bundle");
      httpPost.setHeader("Content-Type", "application/fhir+json");
      httpPost.setHeader("Authorization", authToken);

      JSONObject bundleRequestBody = new JSONObject();
      bundleRequestBody.put("resourceType", "Bundle");
      bundleRequestBody.put("type", "transaction");
      JSONArray observationArray = getObservationEntries(httpClient, authToken, ccn, observationEntries, resourceId, isPatientNew);
      if (observationArray.isEmpty()) {
        // Don't post bundle if there are no observations to post
        return;
      }
      bundleRequestBody.put("entry", observationArray);

      StringEntity stringEntity = new StringEntity(bundleRequestBody.toString());
      httpPost.setEntity(stringEntity);
      httpClient.execute(httpPost);
    } catch (Exception e) {
      return;
    }
  }

  protected JSONArray getObservationEntries(CloseableHttpClient httpClient, String authToken, String ccn, List<BundleEntryComponent> observationEntries, String resourceId, boolean isPatientNew) throws Exception {
    JSONArray entryArray = new JSONArray();

    for (BundleEntryComponent entry : observationEntries) {
      // If observation already exists on server, skip posting of resource
      if (!entry.hasFullUrl()) continue;
      String fullUrl = entry.getFullUrl();

      // Only check if patient isn't new
      if (!isPatientNew) {
        HttpGet httpGet = new HttpGet(cibmtrUrl + "Observation?identifier=" + fullUrl);
        httpGet.setHeader("Content-Type", "application/fhir+json");
        httpGet.setHeader("Authorization", authToken);
        String responseBody = httpClient.execute(httpGet, getResponseHandler);
        if (responseBody != null) {
          JSONObject responseObj = new JSONObject(responseBody.toString());
          if (responseObj.getInt("total") > 0) {
            // Don't add this observation if it already exists
            continue;
          }
        }
      }

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
      JSONObject identifierObject = new JSONObject();
      identifierObject.put("use", "official");
      identifierObject.put("system", RESOURCE_IDENTIFIER_SYSTEM);
      identifierObject.put("value", fullUrl);
      observationResourceObject.put("identifier", (new JSONArray()).put(identifierObject));
      observationObject.put("resource", observationResourceObject);
      entryArray.put(observationObject);
    }

    return entryArray;
  }

  protected JSONObject getMeta(String ccn) {
    JSONObject metaObject = new JSONObject();
    JSONArray securityArray = new JSONArray();
    JSONObject securityObject = new JSONObject();
    securityObject.put("system", CCN_SYSTEM);
    securityObject.put("code", "rc_" + ccn);
    securityArray.put(securityObject);
    metaObject.put("security", securityArray);

    return metaObject;
  }

  // Extracts CCN from MessageHeader.sender.identifier
  protected String getCcn(List<BundleEntryComponent> bundleEntries, MessageHeader messageHeader) {
    Reference sender = messageHeader.getSender();
    String orgReference = sender.getReference();

    // Assuming the organization reference is 'Organization/id'
    if (!orgReference.contains("Organization/")) return null;
    String orgId = orgReference.substring(13);

    // HAPI sometimes includes the resource type in the ID so we need to make 2 comparisons to the entry id
    BundleEntryComponent orgEntry = bundleEntries.stream().filter(entry ->
      entry.getResource().getId() != null
      && (entry.getResource().getId().equals(orgReference)
      || entry.getResource().getId().equals(orgId))
    ).findAny().orElse(null);
    if (orgEntry == null) return null;

    Organization orgResource = (Organization) orgEntry.getResource();
    List<Identifier> ids = orgResource.getIdentifier();
    if (ids != null) {
      for (Identifier id : ids) {
        String system = id.getSystem();
        String value = id.getValue();
        if (system != null && value != null) {
          if (system.equals(CCN_SYSTEM)) return value;
        }
      }
    }

    return null;
  }
}
