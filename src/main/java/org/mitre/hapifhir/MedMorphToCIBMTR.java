package org.mitre.hapifhir;

import java.util.List;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Patient;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MedMorphToCIBMTR {
  public Bundle convert(Bundle medmorphReport) throws MalformedURLException {
    // https://fhir.nmdp.org/ig/cibmtr-reporting/CIBMTR_Direct_FHIR_API_Connection_Guide_STU3.pdf
    if (medmorphReport.hasEntry()) {
      List<BundleEntryComponent> entriesList = medmorphReport.getEntry();
      BundleEntryComponent patientEntry = entriesList.stream().filter(entry -> entry.getResource().getResourceType() == ResourceType.Patient).findAny().orElse(null);
      if (patientEntry != null) {
        Patient pat = (Patient) patientEntry.getResource();
        URL cridUrl = new URL("http://pathways.mitre.org:4444/CRID");
        try {
          HttpURLConnection connection = (HttpURLConnection) cridUrl.openConnection();
          connection.setRequestMethod("PUT");
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type", "application/json");

          JSONObject cridRequestBody = new JSONObject();
          cridRequestBody.put("ccn", "12345");
          JSONObject patientJson = new JSONObject();
          patientJson.put("firstName", pat.getName().get(0));
          patientJson.put("lastName", pat.getName().get(1));
          patientJson.put("birthdate", pat.getBirthDate().toString());
          patientJson.put("gender", pat.getGender().getDisplay());
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
          JSONObject[] perfectMatch = (JSONObject[])responseObj.get("perfectMatch");
          if (perfectMatch.length > 0) {
            String crid = (String)perfectMatch[0].get("crid");

            // POST Patient resource
            URL patientUrl = new URL("http://pathways.mitre.org:4444/Patient");
            HttpURLConnection patientConnection = (HttpURLConnection) patientUrl.openConnection();
            patientConnection.setRequestMethod("POST");
            patientConnection.setDoOutput(true);
            patientConnection.setRequestProperty("Content-Type", "application/fhir+json");

            JSONObject patientRequestBody = new JSONObject();
            patientRequestBody.put("resourceType", "Patient");
            JSONArray securityArray = new JSONArray();
            JSONObject securityObject = new JSONObject();
            securityObject.put("system", "http://cibmtr.org/codesystem/transplant-center");
            securityObject.put("code", "rc_12001");
            securityArray.put(securityObject);
            patientRequestBody.put("meta", (new JSONObject()).put("security", securityArray));
            patientRequestBody.put("text", (new JSONObject()).put("status", "empty"));
            JSONArray identifierArray = new JSONArray();
            JSONObject identifierObject = new JSONObject();
            identifierObject.put("use", "official");
            identifierObject.put("system", "http://cibmtr.org/identifier/CRID");
            identifierObject.put("value", crid);
            patientRequestBody.put("identifier", identifierArray);

          } else {
            return null;
          }
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }
}
