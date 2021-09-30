# MedMorph to CIBMTR

This project provides a library to convert between MedMorph Reporting Bundles and CIBMTR FHIR API transactions.


## About MedMorph

> The Making EHR Data More Available for Research and Public Health (MedMorph) project seeks to advance public health and patient-centered outcomes by using emerging health data and exchange standards, such as Health Level 7 (HL7) Fast Healthcare Interoperability Resources (FHIR) and Clinical Quality Language (CQL), to develop and implement an interoperable solution that will enable access to clinical data. The MedMorph project fits within the Centers for Disease Control and Prevention (CDC) strategic imperative of transforming how data are collected, used, and shared through modern Information Technology (IT) capabilities to save lives and improve health. The MedMorph project is funded by the Health and Human Services (HHS) Assistant Secretary for Planning and Evaluation (ASPE) Patient-Centered Outcomes Research Trust Fund (PCORTF) and executed by the Center for Surveillance, Epidemiology, and Laboratory Services (CSELS) Public Health Informatics Office (PHIO) to advance research and public health goals. 

https://build.fhir.org/ig/HL7/fhir-medmorph/index.html

## About CIBMTR

> The CIBMTR® (Center for International Blood and Marrow Transplant Research®) is a research collaboration between the National Marrow Donor Program® (NMDP)/Be The Match® and the Medical College of Wisconsin (MCW). The CIBMTR collaborates with the global scientific community to advance hematopoietic cell transplantation (HCT) and other cellular therapy worldwide to increase survival and enrich quality of life for patients. The CIBMTR facilitates critical observational and interventional research through scientific and statistical expertise, a large network of transplant centers, and a unique and extensive clinical outcomes database.
> CIBMTR is Copyright © 2004-2021 The Medical College of Wisconsin, Inc. and the National Marrow Donor Program.

https://www.cibmtr.org



## Installation

This project can be added to an existing Maven-based project, add this dependency to `pom.xml`:

```xml
<dependency>
  <groupId>org.mitre.hapifhir</groupId>
  <artifactId>medmorph-to-cibmtr</artifactId>
  <version>0.0.5</version>
</dependency>
```

Or for a Gradle-based project, add this to `build.gradle`:

```gradle
compile 'org.mitre.hapifhir:medmorph-to-cibmtr:0.0.5'
```

## Usage
TBD


## Development

To install the current working version to your local Maven repo, run

```sh
./gradlew publishToMavenLocal
```

### Publishing New Versions

To publish new versions to Maven Central, first update the version in `build.gradle`:

```gradle
def mavenVersion = '0.0.5'
```

Then tag the version as appropriate in GitHub, for example:

```sh
git tag v0.0.5
git push origin v0.0.5
```

The CI process `deploy.yml` will run to publish the new version.

Coordinate with [@dehall](https://github.com/dehall) (dehall@mitre.org) to ensure the published artifacts are released.

## License

Copyright 2021 The MITRE Corporation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
