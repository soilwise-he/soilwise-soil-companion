
# Standardised soil profile data to support global mapping and modelling (WoSIS snapshot 2019)

**Niels H. Batjes, Eloi Ribeiro, and Ad van Oostrum**  
ISRIC – World Soil Information, Wageningen, 6708 PB, the Netherlands  
**Correspondence:** Niels H. Batjes (niels.batjes@isric.org)  

Received: 6 September 2019 – Discussion started: 16 September 2019  
Revised: 17 December 2019 – Accepted: 9 January 2020 – Published: 10 February 2020  

----

**Abstract.** The World Soil Information Service (WoSIS) provides quality-assessed and standardised soil profile data to support digital soil mapping and environmental applications at broadscale levels. Since the release of the first “WoSIS snapshot”, in July 2016, many new soil data were shared with us, registered in the ISRIC data repository and subsequently standardised in accordance with the licences specified by the data providers. Soil profile data managed in WoSIS were contributed by a wide range of data providers; therefore, special attention was paid to measures for soil data quality and the standardisation of soil property definitions, soil property values (and units of measurement) and soil analytical method descriptions. We presently consider the following soil chemical properties: organic carbon, total carbon, total carbonate equivalent, total nitrogen, phosphorus (extractable P, total P and P retention), soil pH, cation exchange capacity and electrical conductivity. We also consider the following physical properties: soil texture (sand, silt, and clay), bulk density, coarse fragments and water retention. Both of these sets of properties are grouped according to analytical procedures that are operationally comparable. Further, for each profile we provide the original soil classification (FAO, WRB, USDA), version and horizon designations, insofar as these have been specified in the source databases. Measures for geographical accuracy (i.e. location) of the point data, as well as a first approximation for the uncertainty associated with the operationally defined analytical methods, are presented for possible consideration in digital soil mapping and subsequent earth system modelling. The latest (dynamic) set of quality-assessed and standardised data, called “wosis_latest”, is freely accessible via an OGC-compliant WFS (web feature service). For consistent referencing, we also provide time-specific static “snapshots”. The present snapshot (September 2019) is comprised of 196,498 geo-referenced profiles originating from 173 countries. They represent over 832,000 soil layers (or horizons) and over 5.8 million records. The actual number of observations for each property varies (greatly) between profiles and with depth, generally depending on the objectives of the initial soil sampling programmes. In the coming years, we aim to fill gradually gaps in the geographic distribution and soil property data themselves, this subject to the sharing of a wider selection of soil profile data for so far under-represented areas and properties by our existing and prospective partners. Part of this work is foreseen in conjunction within the Global Soil Information System (GloSIS) being developed by the Global Soil Partnership (GSP). The “WoSIS snapshot – September 2019” is archived and freely accessible at https://doi.org/10.17027/isric-wdcsoils.20190901 (Batjes et al., 2019).

----

Published by Copernicus Publications.


---


# 1 Introduction

According to a recent review, so far over 800 000 soil profiles have been rescued and compiled into databases over the past few decades (Arrouays et al., 2017). However, only a fraction thereof is readily accessible (i.e. shared) in a consistent format for the greater benefit of the international community. This paper describes procedures for preserving, quality-assessing, standardising and subsequently providing consistent world soil data to the international community, as developed in the framework of the Data or WoSIS (World Soil Information Service) project since the release of the first snapshot in 2016 (Batjes et al., 2017); this collaborative project draws on an increasingly large complement of shared soil profile data. Ultimately, WoSIS aims to provide consistent harmonised soil data, derived from a wide range of legacy holdings as well as from more recently developed soil datasets derived from proximal sensing (e.g. soil spectral libraries; see Terhoeven-Urselmans et al., 2010; Viscarra Rossel et al., 2016), in an interoperable mode and preferably within the setting of a federated, global soil information system (GLOSIS; see GSP-SDF, 2018).

We follow the definition of harmonisation used by the Global Soil Partnership (GSP, Baritz et al., 2014). It encompasses “providing mechanisms for the collation, analysis and exchange of consistent and comparable global soil data and information”. The following domains need to be considered according to GSP’s definition: (a) soil description, classification, and mapping; (b) soil analyses; (c) exchange of digital soil data; and (d) interpretations. In view of the breadth and magnitude of the task, as indicated earlier (Batjes et al., 2017), we have restricted ourselves to the standardisation of soil property definitions, soil analytical method descriptions and soil property values (i.e. measurement units). We have expanded the number of soil properties considered in the preceding snapshot, i.e. those listed in the GlobalSoilMap (2015) specifications, gradually working towards the range of soil properties commonly considered in other global soil data compilation programmes (Batjes, 2016; FAO et al., 2012; van Engelen and Dijkshoorn, 2013).

Soil characterisation data, such as pH and bulk density, are collated according to a wide range of analytical procedures. Such data can be more appropriately used when the procedures for their collection, analysis and reporting are well understood. As indicated by USDA Soil Survey Staff (2011), results differ when different analytical methods are used, even though these methods may carry the same name (e.g. soil pH) or concept. This complicates, or sometimes precludes, comparison of one set of data with another if it is not known how both sets were collected and analysed. Hence, our use of “operational definitions” for soil properties that are linked to specific methods. As an example, we may consider the “pH of a soil”. This requires information on sample pretreatment, soil / solution ratio and description of solution (e.g. H₂O, 1 M KCl, 0.02 M CaCl₂, or 1 M NaF) to be fully understood. The pH level measured in sodium fluoride (pH NaF), for example, provides a measure for the phosphorus (P) retention of a soil, whereas pH measured in water (pH H₂O) is an indicator for soil nutrient status. Consequently, in WoSIS, soil properties are defined by the analytical methods and the terminology used, based on common practice in soil science.

This paper discusses methodological changes in the WoSIS workflow since the release of the preceding snapshot (Batjes et al., 2017), describes the data screening procedure, provides a detailed overview of the database content, explains how the new set of standardised data can be accessed and outlines future developments. The data model for the underpinning PostgreSQL database itself is described in a recently updated procedures manual (Ribeiro et al., 2018); these largely technical aspects are considered beyond the scope of this paper.

Quality-assessed data provided through WoSIS can be (and have been) used for various purposes. For example, as point data for making soil property maps at various spatial-scale levels, using digital soil mapping techniques (Arrouays et al., 2017; Guevara et al., 2018; Hengl et al., 2017a, b; Moulatlet et al., 2017). Such property maps, for example, can be used to study global effects of soil and climate on leaf photosynthetic traits and rates (Maire et al., 2015), generate maps of root zone plant-available water capacity (Leenaars et al., 2018) in support of yield gap analyses (van Ittersum et al., 2013), assess impacts of long-term human land use on world soil carbon stocks (Sanderman et al., 2017), or the effects of tillage practices on soil gaseous emissions (Lutz et al., 2019). In turn, this type of information can help to inform global conventions such as the UNCCD (United Nations Convention to Combat Desertification) and UNFCCC (United Nations Framework Convention on Climate Change) so that policymakers and business leaders can make informed decisions about environmental and societal well-being.

# 2 WoSIS workflow

The overall workflow for acquiring, ingesting and processing data in WoSIS has been described in an earlier paper (Batjes et al., 2017). To avoid repetition, we will only name the main steps here (Fig. 1). These are, successively, (a) store submitted datasets with their metadata (including the licence defining access rights) in the ISRIC Data Repository; (b) import all datasets “as is” into PostgreSQL; (c) ingest the data into the WoSIS data model, including basic data quality assessment and control; (d) standardise the descriptions for the soil analytical methods and the units of measurement; and (e) ultimately, upon final consistency checks, distribute the quality-assessed and standardised data via WFS (web feature service) and other formats (e.g. TSV for snapshots).

As indicated, datasets shared with our centre are first stored in the ISRIC Data Repository, together with their


---


# WoSIS snapshot 2019

----

## ![Workflow icons]  
Data repository → Database format → Database model → Standardise → Distribute

**Figure 1.** Schematic representation of the WoSIS workflow for safeguarding and processing disparate soils datasets.

----

Metadata (currently representing some 452,000 profiles) and the licence and data-sharing agreement in particular, in line with the ISRIC Data Policy (ISRIC, 2016). For the WoSIS standardisation workflow proper, we only consider those datasets (or profiles) that have a “non-restrictive” Creative Commons (CC) licence as well as a defined complement of attributes (see Appendix A). Non-restrictive has been defined here as at least a CC-BY (attribution) or CC-BY-NC (attribution non-commercial) licence. Presently, this corresponds with data for some 196,498 profiles (i.e. profiles that have the right licence and data for at least one of the standard soil properties). Alternatively, some datasets may only be used for digital soil mapping using SoilGrids™, corresponding with an additional 42,000 profiles, corresponding to some 18% of the total amount of standardised profiles (∼ 238,000). Although the latter profiles are quality-assessed and standardised following the regular WoSIS workflow, they are not distributed to the international community in accordance with the underpinning licence agreements; as such, their description is beyond the scope of the present paper. Finally, several datasets have licences indicating that they should only be safeguarded in the repository; inherently, these are not being used for any data processing.

----

## 3 Data screening, quality control and standardisation

### 3.1 Consistency checks

Soil profile data submitted for consideration in WoSIS were collated according to various national or international standards and presented in various formats (from paper to digital). Further, they are of varying degrees of completeness, as discussed below. Proper documentation of the provenance and identification of each dataset and, ideally, each observation or measurement is necessary to allow for efficient processing of the source data. The following need to be specified: profiles and layers referenced by feature (x–y–z) and time (t), attribute (class, site, layer field and layer lab), method, and value, including units of expression.

To be considered in the actual WoSIS standardisation workflow, each profile must meet several criteria (Table 1). First, we assess if each profile is geo-referenced, has (consistently) defined upper and lower depths for each layer (or horizon), and has data for at least some soil properties (e.g. sand, silt, clay and pH). Having a soil (taxonomic) classification is considered desirable (case 1) but not mandatory (case 2). Georeferenced profiles for which only the classification is specified can still be useful for mapping of soil taxonomic classes (case 3). Alternatively, profiles without any geo-reference may still prove useful to develop pedotransfer functions (case 4 and 5); however, they cannot be served through WFS (because there is no geometry, x, y). The remaining cases (6 and 7) are automatically excluded from the WoSIS workflow. This first broad consistency check led to the exclusion of over 50,000 profiles from the initial complement of soil profiles.

Consistency in layer depth (i.e. sequential increase in the upper and lower depth reported for each layer down the profile) is checked using automated procedures (see Sect. 3.2). In accord with current internationally accepted conventions, such depth increments are given as “measured from the surface, including organic layers and mineral covers” (FAO, 2006; Schoeneberger et al., 2012). Prior to 1993, however, the beginning (zero datum) of the profile was set at the top of the mineral surface (the solum proper), except for “thick” organic layers as defined for peat soils (FAO-ISRIC, 1986; FAO, 1977). Organic horizons were recorded as above and mineral horizons recorded as below, relative to the mineral surface (Schoeneberger et al., 2012, pp. 2–6). Insofar as is possible, such “surficial litter” layers are flagged in WoSIS as an auxiliary variable (see Appendix B) so that they may

----

<table>
<thead>
<tr>
<th>Case</th>
<th>(x, y)</th>
<th>Layer depth</th>
<th>Soil properties<sup>a</sup></th>
<th>Classification</th>
<th>Keep</th>
</tr>
</thead>
<tbody>
<tr>
<td>1</td>
<td>+</td>
<td>+</td>
<td>+</td>
<td>+</td>
<td>Yes</td>
</tr>
<tr>
<td>2</td>
<td>+</td>
<td>+</td>
<td>+</td>
<td>−</td>
<td>Yes<sup>a</sup></td>
</tr>
<tr>
<td>3</td>
<td>+</td>
<td>−</td>
<td>−</td>
<td>+</td>
<td>Yes<sup>b</sup></td>
</tr>
<tr>
<td>4</td>
<td>−</td>
<td>+</td>
<td>+</td>
<td>+</td>
<td>Yes/no<sup>b</sup></td>
</tr>
<tr>
<td>5</td>
<td>−</td>
<td>+</td>
<td>+</td>
<td>−</td>
<td>Yes/no<sup>b</sup></td>
</tr>
<tr>
<td>6</td>
<td>+</td>
<td>+</td>
<td>−</td>
<td>−</td>
<td>No</td>
</tr>
<tr>
<td>7</td>
<td>+</td>
<td>−</td>
<td>+</td>
<td>−</td>
<td>No<sup>c</sup></td>
</tr>
</tbody>
</table>

<small>
<sup>a</sup> Such profiles may be used to generate maps of soil taxonomic classes using SoilGrids™ (Hengl et al., 2017b).  
<sup>b</sup> Such profiles (geo-referenced solely according to their country of origin) may be useful for developing pedotransfer functions. Hence, they are standardised, though they are not distributed with the snapshot, as they lack (x, y) coordinates.  
<sup>c</sup> Lacking information on the depth of sampling (i.e. layer), the different soil properties cannot be meaningfully grouped to develop pedotransfer functions.
</small>

----

www.earth-syst-sci-data.net/12/299/2020/  
Earth Syst. Sci. Data, 12, 299–320, 2020


---


be filtered out during auxiliary computations of soil organic carbon stocks, for example.

### 3.2 Flagging duplicate profiles

Several source materials, such as the harmonised WISE soil profile database (Batjes, 2009), the Africa Soil Profile Database (AfSP, Leenaars et al., 2014) and the dataset collated by the International Soil Carbon Network (ISCN, Nave et al., 2017) are compilations of shared soil profile data. These three datasets, for example, contain varying amounts of profiles derived from the National Cooperative Soil Survey database (USDA-NCSS, 2018), an important source of freely shared, primary soil data. The original NCSS profile identifiers, however, may not always have been preserved “as is” in the various data compilations.

To avoid duplication in the WoSIS database, soil profiles located within 100 m of each other are flagged as possible duplicates. Upon additional, semi-automated checks concerning the first three layers (upper and lower depth), i.e. sand, silt and clay content, the duplicates with the least comprehensive component of attribute data are flagged and excluded from further processing. When still in doubt at this stage, additional visual checks are made with respect to other commonly reported soil properties, such as pH_water and organic carbon content. This laborious, yet critical, screening process (see Ribeiro et al., 2018) led to the exclusion of some 50 000 additional profiles from the initial complement of soil profile data.

### 3.3 Ensuring naming consistency

The next key stage has been the standardisation of soil property names to the WoSIS conventions, as well as the standardisation of the soil analytical methods descriptions themselves (see Appendix A). Quality checks consider the units of measurement, plausible ranges for defined soil properties (e.g. soil pH cannot exceed 14) using checks on minimum, average and maximum values for each source dataset. Data that do not fulfil the requirements are flagged and not considered further in the workflow, unless the observed “inconsistencies” can easily be fixed (e.g. blatant typos in pH values). The whole procedure, with flowcharts and option tables, is documented in the WoSIS Procedures Manual (see Appendices D, E and F in Ribeiro et al., 2018).

Presently, we standardise the following set of soil properties in WoSIS.

* **Chemical.** Organic carbon, total carbon (i.e. organic plus inorganic carbon), total nitrogen, total carbonate equivalent (inorganic carbon), soil pH, cation exchange capacity, electrical conductivity and phosphorus (extractable P, total P and P retention).

* **Physical.** Soil texture (sand, silt and clay), coarse fragments, bulk density and water retention.

It should be noted that all measurement values are reported as recorded in the source data, subsequent to the above consistency checks (and standardisation of the units of measurement to the target units; see Appendix A). As such, we neither apply “gap-filling” procedures in WoSIS, e.g. when only the sand and silt fractions are reported, nor do we apply pedotransfer functions to derive soil hydrological properties. This next stage of data processing is seen as the responsibility of the data users (modellers) themselves, as the required functions or means of depth-aggregating the layer data will vary with the projected use(s) of the standardised data (see Finke, 2006; Hendriks et al., 2016; Van Looy et al., 2017).

### 3.4 Providing measures for geographic and attribute accuracy

It is well known that “soil observations used for calibration and interpolation are themselves not error free” (Baroni et al., 2017; Cressie and Kornak, 2003; Folberth et al., 2016; Grimm and Behrens, 2010; Guevara et al., 2018; Hengl et al., 2017b; Heuvelink, 2014; Heuvelink and Brown, 2006). Hence, we provide measures for the geographic accuracy of the point locations as well as the accuracy of the laboratory measurements for possible consideration in digital soil mapping and subsequent earth system modelling (Dai et al., 2019).

All profile coordinates in WoSIS are presented according to the World Geodetic System (i.e. WGS84, EPSG code 4326). These coordinates were converted from a diverse range of national projections. Further, the source referencing may have been in decimal degrees (DD) or expressed in degrees, minutes, and seconds (DMS) for both latitude and longitude. The (approximate) accuracy of georeferencing in WoSIS is given in decimal degrees. If the source only provided degrees, minutes, and seconds (DMS) then the geographic accuracy is set at 0.01; if seconds (DM) are missing it is set at 0.1; and if seconds and minutes (D) are missing it is set at 1. For most profiles (86 %; see Table 2), the approximate accuracy of the point locations, as inferred from the original coordinates given in the source datasets, is less than 10 m (total = 196 498 profiles; see Sect. 4). Typically, the geo-referencing of soil profiles described and sampled before the advent of GPS (Global Positioning Systems) in the 1970s is less accurate; sometimes we just do not know the “true” accuracy. Digital soil mappers should duly consider the inferred geometric accuracy of the profile locations in their applications (Grimm and Behrens, 2010), since the soil observations and covariates may not actually correspond (Cressie and Kornak, 2003) in both space and time (see Sect. 4, second paragraph).

As indicated, soil data considered in WoSIS have been analysed according to a wide range of analytical procedures and in different laboratories. An indication of the measurement uncertainty is thus desired; soil-laboratory-specific Quality Management Systems (van Reeuwijk, 1998), as well


---



<table>
<thead>
<tr>
<th>Decimal places</th>
<th>Decimal degrees</th>
<th>Approximate precision</th>
<th colspan="2">Number of profiles</th>
</tr>
<tr>
<th></th>
<th></th>
<th></th>
<th>n</th>
<th>%</th>
</tr>
</thead>
<tbody>
<tr>
<td>7</td>
<td>0.0000001</td>
<td>1 cm</td>
<td>1345</td>
<td>0.7</td>
</tr>
<tr>
<td>6</td>
<td>0.000001</td>
<td>10 cm</td>
<td>84 945</td>
<td>43.2</td>
</tr>
<tr>
<td>5</td>
<td>0.00001</td>
<td>1 m</td>
<td>74 024</td>
<td>37.7</td>
</tr>
<tr>
<td>4</td>
<td>0.0001</td>
<td>10 m</td>
<td>9158</td>
<td>4.7</td>
</tr>
<tr>
<td>3</td>
<td>0.001</td>
<td>100 m</td>
<td>8108</td>
<td>4.1</td>
</tr>
<tr>
<td>2</td>
<td>0.01</td>
<td>1 km</td>
<td>10 915</td>
<td>5.6</td>
</tr>
<tr>
<td>1</td>
<td>0.1</td>
<td>10 km</td>
<td>6458</td>
<td>3.2</td>
</tr>
<tr>
<td>0</td>
<td>1</td>
<td>100 km</td>
<td>1545</td>
<td>0.8</td>
</tr>
</tbody>
</table>

as laboratory proficiency-testing (PT, Magnusson and Örne­mark, 2014; Munzert et al., 2007; WEPAL, 2019), can provide this type of information. Yet, calculation of laboratory-specific measurement uncertainty for a single method or multiple analytical methods will require several measurement rounds (years of observation) and solid statistical analyses. Overall, such detailed information is not available for the datasets submitted to the ISRIC data repository. Therefore, out of necessity, we have distilled the desired information from the PT literature (Kalra and Maynard, 1991; Rayment and Lyons, 2011; Rossel and McBratney, 1998; van Reeuwijk, 1983; WEPAL, 2019), in so far as technically feasible. For example, accuracy for bulk density measurements, both for the direct core and the clod method, has been termed “low” (though not quantified) in a recent review (Al-Shammary et al., 2018); using expert knowledge, we have assumed this corresponds with an uncertainty (or variability, expressed as coefficient of variation) of 35 %. Alternatively, for organic carbon content the mean variability was 17 % (with a range of 12 % to 42 %) and for “CEC (cation exchange capacity) buffered at pH 7” it was 18 % (range 13 % to 25 %) when multiple laboratories analyse a standard set of reference materials using similar operational methods (WEPAL, 2019). For soil pH measurements (log scale), we have expressed the uncertainty in terms of “±pH units”.

Importantly, the figures for measurement accuracy presented in Appendix A represent first approximations. They are based on the inter-laboratory comparison of well-homogenised reference samples for a still relatively small range of soil types. These indicative figures should be refined once laboratory-specific and method-related accuracy (i.e. systematic and random error) information is provided for the shared soil data, e.g. by using the procedures described by Eurachem (Magnusson and Örnemark, 2014). Alternatively, this type of information may be refined in the context of international laboratory PT networks, such as GLOSOLAN and WEPAL. Meanwhile, the present “first” estimates may already be considered to calculate the accuracy of digital soil maps and of any interpretations derived from them (e.g. maps of soil organic carbon stocks in support of the UNCCD Land Degradation Neutrality, LDN, effort).

## 4 Spatial distribution of soil profiles and number of observations

The present snapshot includes standardised data for 196 498 profiles (Fig. 2), about twice the amount represented in the “July 2016” snapshot. These are represented by some 832 000 soil layers (or horizons). In total, this corresponds with over 5.8 million records that include both numeric (e.g. sand content, soil pH and cation exchange capacity) and class (e.g. WRB soil classification and horizon designation) properties. The naming conventions and standard units of measurement are provided in Appendix A, and the file structure is provided in Appendix B.

Being a compilation of national soil data, the profiles were sampled over a long period of time. The dates reported in the snapshot will reflect the year the respective data were sampled and analysed: 1397 (0.7 %) profiles were sampled before 1920, 218 (0.1 %) between 1921 and 1940, 7,657 (3.9 %) between 1941 and 1960, 26,614 (13.5 %) between 1961 and 1980, 62 691 (31.9 %) between 1981 and 2000, and 31 084 (15.8 %) between 2001 and 2020, while the date of sampling is unknown for 66 837 profiles (34.0 %). This information should be taken into consideration when linking the point data with environmental covariates, such as land use, in digital soil mapping.

The number of profiles per continent is highest for North America (73 604 versus 63 066 in the “2016” snapshot), followed by Oceania (42 918 versus 235), Europe (35 311 versus 1,908), Africa (27 688 versus 17 153), South America (10 218 versus 8790), Asia (6704 versus 3089) and Antarctica (9, no change). These profiles come from 173 countries; the average density of observations is 1.35 profiles per 1000 km². The actual density of observations varies greatly, both between countries (Appendix C) and within each country, with the largest densities of “shared” profiles reported for Belgium (228 profiles per 1000 km²) and Switzerland (265 profiles per 1000 km²). There are still relatively few profiles for Central Asia, Southeast Asia, Central and Eastern Europe, Russia, and the northern circumpolar region. The number of profiles by biome (R. J. Olson et al., 2001) or broad climatic region (Sayre et al., 2014), as derived from GIS overlays, is provided in Appendix D for additional information.

There are more observations for the chemical data than the physical data (see Appendix A) and the number of observations generally decreases with depth, largely depending on the objectives of the original soil surveys. The interquartile range for maximum depth of soil sampled in the field is 56–152 cm, with a median of 110 cm (mean = 117 cm). In this respect, it should be noted that some specific purpose surveys only considered the topsoil (e.g. soil fertility surveys),


---


Figure 2. Location of soil profiles provided in the “September 2019” snapshot of WoSIS; see Appendix C for the number and density of profiles by country.

While others systematically sampled soil layers up to depths exceeding 20 m.

Present gaps in the geographic distribution (Appendices C and D) and range of soil attribute data (Appendix A) will gradually be filled in the coming years, though this largely depends on the willingness or ability of data providers to share (some of) their data for consideration in WoSIS. For the northern boreal and Arctic region, for example, ISRIC will regularly ingest new profile data collated by the International Soil Carbon Network (ISCN, Malhotra et al., 2019). Alternatively, it should be reiterated that for some regions, such as Europe (e.g. EU LUCAS topsoil database; see Tóth et al., 2013) and the state of Victoria (Australia), there are holdings in the ISRIC repository that may only be used and standardised for SoilGrids™ applications due to licence restrictions. Consequently, the corresponding profiles (∼ 42 000) are neither shown in Fig. 2 nor are considered in the descriptive statistics in Appendix C.

## 5 Distributing the standardised data

Upon their standardisation, the data are distributed through ISRIC’s SDI (Spatial Data Infrastructure). This web platform is based on open-source technologies and open web-services (WFS, WMS, WCS, CSW) following Open Geospatial Consortium (OGC) standards and is aimed specifically at handling soil data; our metadata are organised following standards of the International Organization for Standardization (ISO-28258, 2013) and are INSPIRE (2015) compliant. The three main components of the SDI are PostgreSQL + PostGIS, GeoServer and GeoNetwork. Visualisation and data download are done in GeoNetwork with resources from GeoServer (https://data.isric.org, last access: 12 September 2019). The third component is the PostgreSQL database, with the spatial extension PostGIS, in which WoSIS resides; the database is connected to GeoServer to permit data download from GeoNetwork. These processes are aimed at facilitating global data interoperability and citeability in compliance with FAIR principles: the data should be “findable, accessible, interoperable and reusable” (Wilkinson et al., 2016). With partners, steps are being taken towards the development of a federated and ultimately interoperable spatial soil data infrastructure (GLOSIS) through which source data are served and updated by the respective data providers and made queryable according to a common SoilML standard (OGC, 2019).

The procedure for accessing the most current set of standardised soil profile data (“wosis_latest”), either from R or QGIS using WFS, is explained in a detailed tutorial (Rossiter, 2019). This dataset is dynamic; hence, it will grow when new point data are shared and processed, additional soil attributes are considered in the WoSIS workflow, and/or when possible corrections are required. Potential errors may be reported online via a “Google group” so that they may be addressed in the dynamic version (register via: https://groups.google.com/forum/#!forum/isric-world-soil-information last access: 15 January 2020).

For consistent citation purposes, we provide static snapshots of the standardised data, in a tab-separated values format, with unique DOI’s (digital object identifier); as indicated, this paper describes the second WoSIS snapshot.

## 6 Discussion

The above procedures describe standardisation according to operational definitions for soil properties. Importantly, it


---


should be stressed here that the ultimate, desired full harmonisation to an agreed reference method *y*, for example, “pH H₂O, 1 : 2.5 soil / water solution” for all “pH 1 : *x* H₂O” measurements, will first become feasible once the target method (*y*) for each property has been defined and subsequently accepted by the international soil community. A next step would be to collate and develop “comparative” datasets for each soil property, i.e. sets with samples analysed according to a given reference method (*Yᵢ*) and the corresponding national methods (*Xⱼ*) for pedotransfer function development. In practice, however, such relationships will often be soil type and region specific (see Appendix C in GlobalSoilMap, 2015). Alternatively, according to GLOSOLAN (Suvannang et al., 2018, p. 10) “comparable and useful soil information (at the global level) will only be attainable once laboratories agree to follow common standards and norms”. In such a collaborative process, it will be essential to consider the end user’s requirements in terms of quality and applicability of the data for their specific purposes (i.e. fitness for intended use). Over the years, many organisations have individually developed and implemented analytical methods and quality assurance systems that are well suited for their countries (e.g. Soil Survey Staff, 2014a) or regions (Orgiazzi et al., 2018) and thus, pragmatically, may not be inclined to implement the anticipated GLOSOLAN standard analytical methods.

## 7 Data availability

Snapshot “WoSIS_2019_September” is archived for long-term storage at ISRIC – World Soil Information, the World Data Centre for Soils (WDC-Soils) of the ISC (International Council for Science, formerly ICSU) World Data System (WDS). It is freely accessible at https://doi.org/10.17027/isric-wdcsoils.20190901 (Batjes et al., 2019). The zip file (154 Mb) includes a “readme first” file that describes key aspects of the dataset (see also Appendix B) with reference to the WoSIS Procedures Manual (Ribeiro et al., 2018), and the data itself in TSV format (1.8 Gb, decompressed) and GeoPackage format (2.2 Gb decompressed).

## 8 Conclusions

The second WoSIS snapshot provides consistent, standardised data for some 196 000 profiles worldwide. However, as described, there are still important gaps in terms of geographic distribution as well as the range of soil taxonomic units and/or properties represented. These issues will be addressed in future releases, depending largely on the success of our targeted requests and searches for new data providers and/or partners worldwide.

* We will increasingly consider data derived by soil spectroscopy and emerging innovative methods. Further, long-term time series at defined locations will be sought to support space–time modelling of soil properties, such as changes in soil carbon stocks or soil salinity.

* We provide measures for geographic accuracy of the point data, as well as a first approximation for the uncertainty associated with the operationally defined analytical methods. This information may be used to assess uncertainty in digital soil mapping and earth system modelling efforts that draw on the present set of point data.

* Capacity building and cooperation among (inter)national soil institutes will be necessary to create and share ownership of the soil information newly derived from the shared data and to strengthen the necessary expertise and capacity to further develop and test the world soil information service worldwide. Such activities may be envisaged within the broader framework of the Global Soil Partnership and emerging GLOSIS system.


---


# Appendix A

<table>
<thead>
<tr>
<th>Code</th>
<th>Property</th>
<th>Units</th>
<th>Profiles</th>
<th>Layers</th>
<th>Description</th>
<th>Accuracy (± %)a</th>
</tr>
</thead>
<tbody>
<tr><td colspan="7"><b>Layer data</b></td></tr>
<tr>
<td>BDFI33</td>
<td>Bulk density fine earth – 33 kPa</td>
<td>kg dm<sup>−3</sup></td>
<td>14 924</td>
<td>78 215</td>
<td>Bulk density of the fine-earth fraction<sup>b</sup>, equilibrated at 33 kPa</td>
<td>35</td>
</tr>
<tr>
<td>BDFIAD</td>
<td>Bulk density fine earth – air dry</td>
<td>kg dm<sup>−3</sup></td>
<td>1786</td>
<td>8471</td>
<td>Bulk density of the fine-earth fraction, air dried</td>
<td>35</td>
</tr>
<tr>
<td>BDFIFM</td>
<td>Bulk density fine earth – field moist</td>
<td>kg dm<sup>−3</sup></td>
<td>5279</td>
<td>14 219</td>
<td>Bulk density of the fine-earth fraction, field moist</td>
<td>35</td>
</tr>
<tr>
<td>BDFIOD</td>
<td>Bulk density fine earth – oven dry</td>
<td>kg dm<sup>−3</sup></td>
<td>25 124</td>
<td>122 693</td>
<td>Bulk density of the fine-earth fraction, oven dry</td>
<td>35</td>
</tr>
<tr>
<td>BDWS33</td>
<td>Bulk density whole soil – 33 kPa</td>
<td>kg dm<sup>−3</sup></td>
<td>26 268</td>
<td>154 901</td>
<td>Bulk density of the whole soil, including coarse fragments, equilibrated at 33 kPa</td>
<td>35</td>
</tr>
<tr>
<td>BDWSAD</td>
<td>Bulk density whole soil – air dry</td>
<td>kg dm<sup>−3</sup></td>
<td>0</td>
<td>0</td>
<td>Bulk density of the whole soil, including coarse fragments, air dried</td>
<td>35</td>
</tr>
<tr>
<td>BDWSFM</td>
<td>Bulk density whole soil – field moist</td>
<td>kg dm<sup>−3</sup></td>
<td>0</td>
<td>0</td>
<td>Bulk density of the whole soil, including coarse fragments, field moist</td>
<td>35</td>
</tr>
<tr>
<td>BDWSOD</td>
<td>Bulk density whole soil – oven dry</td>
<td>kg dm<sup>−3</sup></td>
<td>14 588</td>
<td>75 422</td>
<td>Bulk density of the whole soil, including coarse fragments, oven dry</td>
<td>35</td>
</tr>
<tr>
<td>CECPH7</td>
<td>Cation exchange capacity – buffered at pH7</td>
<td>cmol(c) kg<sup>−1</sup></td>
<td>54 278</td>
<td>295 688</td>
<td>Capacity of the fine-earth fraction to hold exchangeable cations, estimated by buffering the soil at “pH 7”</td>
<td>20</td>
</tr>
<tr>
<td>CECPH8</td>
<td>Cation exchange capacity – buffered at pH8</td>
<td>cmol(c) kg<sup>−1</sup></td>
<td>6422</td>
<td>23 691</td>
<td>Capacity of the fine-earth fraction to hold exchangeable cations, estimated by buffering the soil at “pH 8”</td>
<td>20</td>
</tr>
<tr>
<td>CFGR</td>
<td>Coarse fragments gravimetric total</td>
<td>g per 100 g</td>
<td>39 527</td>
<td>203 083</td>
<td>Gravimetric content of coarse fragments in the whole soil</td>
<td>20</td>
</tr>
<tr>
<td>CFVO</td>
<td>Coarse fragments volumetric total</td>
<td>cm<sup>3</sup> per 100 cm<sup>3</sup></td>
<td>45 918</td>
<td>235 002</td>
<td>Volumetric content of coarse fragments in the whole soil</td>
<td>30</td>
</tr>
<tr>
<td>CLAY</td>
<td>Clay total</td>
<td>g per 100 g</td>
<td>141 640</td>
<td>607 861</td>
<td>Gravimetric content of &lt; x mm soil material in the fine-earth fraction (e.g. <i>x</i> = 0.002 mm, as specified in the analytical method description)<sup>b,c</sup></td>
<td>15</td>
</tr>
<tr>
<td>ECEC</td>
<td>Effective cation exchange capacity</td>
<td>cmol(c) kg<sup>−1</sup></td>
<td>31 708</td>
<td>132 922</td>
<td>Capacity of the fine-earth fraction to hold exchangeable cations at the pH of the soil (ECEC). Conventionally approximated by summation of exchangeable bases (Ca<sup>2+</sup>, Mg<sup>2+</sup>, K<sup>+</sup> and Na<sup>+</sup>) plus 1 N KCl exchangeable acidity (Al<sup>3+</sup> and H<sup>+</sup>) in acidic soils</td>
<td>25</td>
</tr>
<tr>
<td>ELCO20</td>
<td>Electrical conductivity – ratio 1 : 2</td>
<td>dS m<sup>−1</sup></td>
<td>8010</td>
<td>44 596</td>
<td>Ability of a 1 : 2 soil–water extract to conduct electrical current</td>
<td>10</td>
</tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Code</th>
<th>Property</th>
<th>Units</th>
<th>Profiles</th>
<th>Layers</th>
<th>Description</th>
<th>Accuracy (± %)<sup>a</sup></th>
</tr>
</thead>
<tbody>
<tr>
<td>ELCO25</td>
<td>Electrical conductivity – ratio 1 : 2.5</td>
<td>dS m<sup>−1</sup></td>
<td>3313</td>
<td>15 134</td>
<td>Ability of a 1 : 2.5 soil–water extract to conduct electrical current</td>
<td>10</td>
</tr>
<tr>
<td>ELCO50</td>
<td>Electrical conductivity – ratio 1 : 5</td>
<td>dS m<sup>−1</sup></td>
<td>23 093</td>
<td>90 944</td>
<td>Ability of a 1 : 5 soil–water extract to conduct electrical current</td>
<td>10</td>
</tr>
<tr>
<td>ELCOSP</td>
<td>Electrical conductivity – saturated paste</td>
<td>dS m<sup>−1</sup></td>
<td>19 434</td>
<td>73 517</td>
<td>Ability of a water-saturated soil paste to conduct electrical current (EC<sub>e</sub>)</td>
<td>10</td>
</tr>
<tr>
<td>NITKJD</td>
<td>Total nitrogen (N)</td>
<td>g kg<sup>−1</sup></td>
<td>65 356</td>
<td>21 6362</td>
<td>
The sum of total Kjeldahl nitrogen (ammonia, organic and reduced nitrogen) and nitrate–nitrite
</td>
<td>10</td>
</tr>
<tr>
<td>ORGC</td>
<td>Organic carbon</td>
<td>g kg<sup>−1</sup></td>
<td>110 856</td>
<td>471 301</td>
<td>Gravimetric content of organic carbon in the fine-earth fraction</td>
<td>15</td>
</tr>
<tr>
<td>PHAQ</td>
<td>pH H<sub>2</sub>O</td>
<td>unitless</td>
<td>130 986</td>
<td>613 322</td>
<td>
A measure of the acidity or alkalinity in soils, defined as the negative logarithm (base 10) of the activity of hydronium ions (H<sup>+</sup>) in water
</td>
<td>0.3</td>
</tr>
<tr>
<td>PHCA</td>
<td>pH CaCl<sub>2</sub></td>
<td>unitless</td>
<td>66 921</td>
<td>314 230</td>
<td>
A measure of the acidity or alkalinity in soils, defined as the negative logarithm (base 10) of the activity of hydronium ions (H<sup>+</sup>) in a CaCl<sub>2</sub> solution, as specified in the analytical method descriptions
</td>
<td>0.3</td>
</tr>
<tr>
<td>PHKC</td>
<td>pH KCl</td>
<td>unitless</td>
<td>32 920</td>
<td>150 447</td>
<td>
A measure of the acidity or alkalinity in soils, defined as the negative logarithm (base 10) of the activity of hydronium ions (H<sup>+</sup>) in a KCl solution, as specified in the analytical method descriptions
</td>
<td>0.3</td>
</tr>
<tr>
<td>PHNF</td>
<td>pH NaF</td>
<td>unitless</td>
<td>4978</td>
<td>25 448</td>
<td>
A measure of the acidity or alkalinity in soils, defined as the negative logarithm (base 10) of the activity of hydronium ions (H<sup>+</sup>) in a NaF solution, as specified in the analytical method descriptions
</td>
<td>0.3</td>
</tr>
<tr>
<td>PHPBYI</td>
<td>Phosphorus (P) – Bray-I</td>
<td>mg kg<sup>−1</sup></td>
<td>10 735</td>
<td>40 486</td>
<td>
Measured according to the Bray-I method, a combination of HCl and NH<sub>4</sub>F to remove easily acid soluble P forms, largely Al and Fe phosphates (for acid soils)
</td>
<td>40</td>
</tr>
<tr>
<td>PHPMH3</td>
<td>Phosphorus (P) – Mehlich-3</td>
<td>mg kg<sup>−1</sup></td>
<td>1446</td>
<td>7242</td>
<td>
Measured according to the Mehlich-3 extractant, a combination of acids (acetic [HOAc] and nitric [HNO<sub>3</sub>]), salts (ammonium fluoride [NH<sub>4</sub>F] and ammonium nitrate [NH<sub>4</sub>NO<sub>3</sub>]), and the chelating agent ethylenediaminetetraacetic acid (EDTA); considered suitable for removing P and other elements in acid and neutral soils
</td>
<td>25</td>
</tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Code</th>
<th>Property</th>
<th>Units</th>
<th>Profiles</th>
<th>Layers</th>
<th>Description</th>
<th>Accuracy (± %)a</th>
</tr>
</thead>
<tbody>
<tr>
<td>PHPOLS</td>
<td>Phosphorus (P) – Olsen</td>
<td>mg kg⁻¹</td>
<td>2162</td>
<td>8434</td>
<td>Measured according to the Olsen P method: 0.5 M sodium bicarbonate (NaHCO₃) solution at a pH of 8.5 to extract P from calcareous, alkaline and neutral soils</td>
<td>25</td>
</tr>
<tr>
<td>PHPRTN</td>
<td>Phosphorus (P) – retention</td>
<td>mg kg⁻¹</td>
<td>4636</td>
<td>23 917</td>
<td>Retention measured according to the New Zealand method</td>
<td>20</td>
</tr>
<tr>
<td>PHPTOT</td>
<td>Phosphorus (P) – total</td>
<td>mg kg⁻¹</td>
<td>4022</td>
<td>12 976</td>
<td>Determined with a very strong acid (aqua regia and sulfuric acid or nitric acid)</td>
<td>15</td>
</tr>
<tr>
<td>PHPWSL</td>
<td>Phosphorus (P) – water soluble</td>
<td>mg kg⁻¹</td>
<td>283</td>
<td>1242</td>
<td>Measured in 1 : x soil:water solution (mainly determines P in dissolved forms)</td>
<td>15</td>
</tr>
<tr>
<td>SAND</td>
<td>Sand total</td>
<td>g per 100 g</td>
<td>105 547</td>
<td>491 810</td>
<td>The y to z mm fraction of the fine-earth fraction and z upper limit, as specified in the analytical method description for the sand fraction (e.g. y = 0.05 mm to z = 2 mm)<sup>c</sup></td>
<td>15</td>
</tr>
<tr>
<td>SILT</td>
<td>Silt total</td>
<td>g per 100 g</td>
<td>133 938</td>
<td>575 913</td>
<td>x to y mm fraction of the fine-earth fraction and x upper limit, as specified in the analytical method description for the clay fraction (e.g. x = 0.002 mm to y = 0.05 mm)<sup>c</sup></td>
<td>15</td>
</tr>
<tr>
<td>TCEQ</td>
<td>Calcium carbonate equivalent total</td>
<td>g kg⁻¹</td>
<td>51 991</td>
<td>222 242</td>
<td>The content of carbonate in a liming material or calcareous soil calculated as if all of the carbonate is in the form of CaCO₃ (in the fine-earth fraction), also known as inorganic carbon</td>
<td>10</td>
</tr>
<tr>
<td>TOTC</td>
<td>Total carbon (C)</td>
<td>g kg⁻¹</td>
<td>32 662</td>
<td>109 953</td>
<td>Gravimetric content of organic carbon and inorganic carbon in the fine-earth fraction</td>
<td>10</td>
</tr>
<tr>
<td>WG0006</td>
<td>Water retention gravimetric – 6 kPa</td>
<td>g per 100 g</td>
<td>863</td>
<td>4264</td>
<td>Soil moisture content by weight, at tension 6 kPa (pF 1.8)</td>
<td>20</td>
</tr>
<tr>
<td>WG0010</td>
<td>Water retention gravimetric – 10 kPa</td>
<td>g per 100 g</td>
<td>3357</td>
<td>14 739</td>
<td>Soil moisture content by weight, at tension 10 kPa (pF 2.0)</td>
<td>20</td>
</tr>
<tr>
<td>WG0033</td>
<td>Water retention gravimetric – 33 kPa</td>
<td>g per 100 g</td>
<td>21 116</td>
<td>96 354</td>
<td>Soil moisture content by weight, at tension 33 kPa (pF 2.5)</td>
<td>20</td>
</tr>
<tr>
<td>WG0100</td>
<td>Water retention gravimetric – 100 kPa</td>
<td>g per 100 g</td>
<td>696</td>
<td>3762</td>
<td>Soil moisture content by weight, at tension 100 kPa (pF 3.0)</td>
<td>20</td>
</tr>
<tr>
<td>WG0200</td>
<td>Water retention gravimetric – 200 kPa</td>
<td>g per 100 g</td>
<td>4418</td>
<td>28 239</td>
<td>Soil moisture content by weight, at tension 200 kPa (pF 3.3)</td>
<td>20</td>
</tr>
<tr>
<td>WG0500</td>
<td>Water retention gravimetric – 500 kPa</td>
<td>g per 100 g</td>
<td>344</td>
<td>1716</td>
<td>Soil moisture content by weight, at tension 500 kPa (pF 3.7)</td>
<td>20</td>
</tr>
<tr>
<td>WG1500</td>
<td>Water retention gravimetric – 1500 kPa</td>
<td>g per 100 g</td>
<td>34 365</td>
<td>187 176</td>
<td>Soil moisture content by weight, at tension 1500 kPa (pF 4.2)</td>
<td>20</td>
</tr>
<tr>
<td>WV0006</td>
<td>Water retention volumetric – 6 kPa</td>
<td>cm³ per 100 cm³</td>
<td>9</td>
<td>26</td>
<td>Soil moisture content by volume, at tension 6 kPa (pF 1.8)</td>
<td>20</td>
</tr>
<tr>
<td>WV0010</td>
<td>Water retention volumetric – 10 kPa</td>
<td>cm³ per 100 cm³</td>
<td>1469</td>
<td>5434</td>
<td>Soil moisture content by volume, at tension 10 kPa (pF 2.0)</td>
<td>20</td>
</tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Code</th>
<th>Property</th>
<th>Units</th>
<th>Profiles</th>
<th>Layers</th>
<th>Description</th>
<th>Accuracy (± %)a</th>
</tr>
</thead>
<tbody>
<tr>
<td>WV0033</td>
<td>Water retention volumetric – 33 kPa</td>
<td>cm³ per 100 cm³</td>
<td>5987</td>
<td>17 801</td>
<td>Soil moisture content by volume, at tension 33 kPa (pF 2.5)</td>
<td>20</td>
</tr>
<tr>
<td>WV0100</td>
<td>Water retention volumetric – 100 kPa</td>
<td>cm³ per 100 cm³</td>
<td>747</td>
<td>2559</td>
<td>Soil moisture content by volume, at tension 100 kPa (pF 3.0)</td>
<td>20</td>
</tr>
<tr>
<td>WV0200</td>
<td>Water retention volumetric – 200 kPa</td>
<td>cm³ per 100 cm³</td>
<td>3</td>
<td>9</td>
<td>Soil moisture content by volume, at tension 200 kPa (pF 3.3)</td>
<td>20</td>
</tr>
<tr>
<td>WV0500</td>
<td>Water retention volumetric – 500 kPa</td>
<td>cm³ per 100 cm³</td>
<td>703</td>
<td>1763</td>
<td>Soil moisture content by volume, at tension 500 kPa (pF 3.7)</td>
<td>20</td>
</tr>
<tr>
<td>WV1500</td>
<td>Water retention volumetric – 1500 kPa</td>
<td>cm³ per 100 cm³</td>
<td>6149</td>
<td>17 542</td>
<td>Soil moisture content by volume, at tension 1500 kPa (pF 4.2)</td>
<td>20</td>
</tr>
<tr>
<td colspan="7"><b>Site data</b></td>
</tr>
<tr>
<td>CSTX</td>
<td>Soil classification Soil taxonomy</td>
<td>classes</td>
<td>21 314</td>
<td>n/a</td>
<td>Classification of the soil profile, according to the specified edition (year) of USDA Soil Taxonomy, up to subgroup level when available</td>
<td>–</td>
</tr>
<tr>
<td>CWRB</td>
<td>Soil classification WRB</td>
<td>classes</td>
<td>26 664</td>
<td>n/a</td>
<td>Classification of the soil profile, according to the specified edition (year) of the World Reference Base for Soil Resources (WRB), up to qualifier level when available</td>
<td>–</td>
</tr>
<tr>
<td>CFAO</td>
<td>Soil classification FAO</td>
<td>classes</td>
<td>23 890</td>
<td>n/a</td>
<td>Classification of the soil profile, according to the specified edition (year) of the FAO-Unesco Legend, up to soil unit level when available</td>
<td>–</td>
</tr>
<tr>
<td>DSDS</td>
<td>Depth of soil – sampled</td>
<td>cm</td>
<td>196 381</td>
<td>n/a</td>
<td>Maximum depth of soil described and sampled (calculated)</td>
<td>–</td>
</tr>
<tr>
<td>HODS</td>
<td>Horizon designation</td>
<td>–</td>
<td>80 849</td>
<td>396 522</td>
<td>Horizon designation as provided in the source database<sup>d</sup></td>
<td>–</td>
</tr>
</tbody>
</table>

> a Inferred accuracy (or uncertainty), rounded to the nearest 5 %, unless otherwise indicated (i.e. units for soil pH), as derived from the following sources: Al-Shammary et al. (2018), Kalra and Maynard (1991), Rayment and Lyons (2011), Rossel and McBratney (1998), van Reeuwijk (1983), WEPAL (2019). These figures are first approximations that will be fine-tuned once more specific results of laboratory proficiency tests, from national Soil Quality Management systems, become available.  
> b Generally, the fine-earth fraction is defined as being < 2 mm. Alternatively, an upper limit of 1 mm was used in the former Soviet Union and its satellite states (Katchynsky scheme). This has been indicated in the file “wosis_201907_layers_chemical.tsv” and “wosis_201907_layer_physicals.tsv” for those soil properties where this differentiation is important (see “sample pretreatment” in string “xxxx_method” in Appendix B).  
> c Provided only when the sum of clay, silt and sand fraction is ≥ 90 % and ≤ 100 %.  
> d Where available, the “cleaned” (original) layer and horizon designation is provided for general information; these codes have not been standardised as they vary widely between different classification systems (Bridges, 1993; Gerasimova et al., 2013). When horizon designations are not provided in the source databases, we have flagged all layers with an upper depth given as being negative (e.g. −10 to 0 cm under pre-1993 conventions; see text and the WoSIS Procedures Manual 2018; Ribeiro et al., 2018, p. 24, footnote 9) in the source databases as likely being “litter” layers. n/a – not applicable


---


# Appendix B: Structure of the “September 2019” WoSIS snapshot

This Appendix describes the structure of the data files presented in the “September 2019” WoSIS snapshot:

* `wosis_201909_attributes.tsv`,
* `wosis_201909_profiles.tsv`,
* `wosis_201909_layers_chemical.tsv`,
* `wosis_201909_layer_physicals.tsv`.

`wosis_201909_attributes.tsv`. This file lists the four to six letter codes for each attribute, whether the attribute is a site or horizon property, the unit of measurement, the number of profiles and layers represented in the snapshot, and a brief description of each attribute, as well as the inferred uncertainty for each property (Appendix A).

`wosis_201909_profiles.tsv`. This file contains the unique profile ID (i.e. primary key), the source of the data, country ISO code and name, accuracy of geographical coordinates, latitude and longitude (WGS 1984), point geometry of the location of the profile, and the maximum depth of soil described and sampled, as well as information on the soil classification system and edition (Table B1). Depending on the soil classification system used, the number of fields will vary. For example, for the World Soil Reference Base (WRB) system these are as follows: publication_year (i.e. version), reference_soil_group_code, reference_soil_group_name, and the name(s) of the prefix (primary) qualifier(s) and suffix (supplementary) qualifier(s). The terms principal qualifier and supplementary qualifier are currently used (IUSS Working Group WRB, 2015); earlier WRB versions used prefix and suffix for this (e.g. IUSS Working Group WRB, 2006). Alternatively, for USDA Soil Taxonomy, the version (year), order, suborder, great group and subgroup can be accommodated (Soil Survey Staff, 2014b). Inherently, the number of records filled will vary between (and within) the various source databases.

`wosis_201909_layer_chemical.tsv` and `wosis_201909_layer_physical.tsv`. Data for the various layers (or horizons) are presented in two separate files in view of their size (i.e. one for the chemical and one for the physical soil properties). The file structure is described in Table B1.

**Format.** All fields in the above files are delimited by tab, with double quotation marks as text delimiters. File coding is according to the UTF-8 unicode transformation format.

**Using the data.** The above TSV files can easily be imported into an SQL database or statistical software such as R, after which they may be joined using the unique `profile_id`. Guidelines for handling and querying the data are provided in the WoSIS Procedures Manual (Ribeiro et al., 2018, pp. 45–48); see also the detailed tutorial by Rossiter (2019).


---



<table>
<thead>
<tr>
<th>File name/Property</th>
<th>Description</th>
</tr>
</thead>
<tbody>
<tr>
<td><i>wosis_201909_profiles.tsv</i><br>profile_id</td>
<td>This file specifies the main characteristics of a soil profile<br>Primary key</td>
</tr>
<tr>
<td>dataset_id</td>
<td>Identifier for source dataset</td>
</tr>
<tr>
<td>country_id</td>
<td>ISO code for country name</td>
</tr>
<tr>
<td>country_name</td>
<td>Country name (in English)</td>
</tr>
<tr>
<td>geom_accuracy</td>
<td>Accuracy of the geographical coordinates in degrees, e.g if degrees, minutes and seconds are provided in the source then geom_accuracy is set at 0.01, if seconds are missing it is set at 0.1, and if seconds and minutes are missing it is set at 1</td>
</tr>
<tr>
<td>latitude</td>
<td>Latitude in degrees (WGS84)</td>
</tr>
<tr>
<td>longitude</td>
<td>Longitude in degrees (WGS84)</td>
</tr>
<tr>
<td>dsds</td>
<td>Maximum depth of soil described and sampled (calculated)</td>
</tr>
<tr>
<td>cfao_version</td>
<td>Version of FAO legend (e.g. 1974 or 1988)</td>
</tr>
<tr>
<td>cfao_major_group_code</td>
<td>Code for major group (in given version of the legend)</td>
</tr>
<tr>
<td>cfao_major_group</td>
<td>Name of major group</td>
</tr>
<tr>
<td>cfao_soil_unit_code</td>
<td>Code for soil unit</td>
</tr>
<tr>
<td>cfao_soil_unit</td>
<td>Name of soil unit</td>
</tr>
<tr>
<td>cwrb_version</td>
<td>Version of World Reference Base for Soil Resources</td>
</tr>
<tr>
<td>cwrb_reference_soil_group_code</td>
<td>Code for WRB group (in given version of WRB)</td>
</tr>
<tr>
<td>cwrb_reference_soil_group</td>
<td>Full name for reference soil group</td>
</tr>
<tr>
<td>cwrb_prefix_qualifier</td>
<td>Name for prefix (e.g. for WRB1988) or principal qualifier (e.g. for WRB2015)</td>
</tr>
<tr>
<td>cwrb_suffix_qualifier</td>
<td>Name for suffix (e.g. for WRB1988) or supplementary qualifier (e.g. for WRB2015)</td>
</tr>
<tr>
<td>cstx_version</td>
<td>Version of USDA Soil Taxonomy (UST)</td>
</tr>
<tr>
<td>cstx_order_name</td>
<td>Name of UST order</td>
</tr>
<tr>
<td>cstx_suborder</td>
<td>Name of UST suborder</td>
</tr>
<tr>
<td>cstx_great_group</td>
<td>Name of UST great group</td>
</tr>
<tr>
<td>cstx_subgroup</td>
<td>Name of UST subgroup</td>
</tr>
<tr>
<td><i>wosis_201909_layer_chemical.tsv and wosis_201909_layer_physical.tsv</i><br>profile_id</td>
<td>The layer (horizon) data are presented in two separate files in view of their size, one for the chemical and one for the physical soil properties. Both files have the same structure.<br>Identifier for profile, foreign key to ‘wosis_201909_profiles’</td>
</tr>
<tr>
<td>profile_layer_id</td>
<td>Unique identifier for layer for given profile (primary key)</td>
</tr>
<tr>
<td>upper_depth</td>
<td>Upper depth of layer (or horizon; cm)</td>
</tr>
<tr>
<td>lower_depth</td>
<td>Lower depth of layer (cm)</td>
</tr>
<tr>
<td>layer_name</td>
<td>Name of the horizon, as provided in the source data</td>
</tr>
<tr>
<td>litter</td>
<td>Flag (Boolean) indicating whether this is considered a surficial litter layer</td>
</tr>
<tr>
<td>xxxx_value*</td>
<td>Array listing all measurement values for soil property “xxxx” (e.g. BDFI33 or PHAQ) for the given layer. In some cases, more than one observation is reported for a given horizon (layer) in the source, for example, four values for TOTC: {1 : 5.4, 2 : 8.2, 3 : 6.3, 4 : 7.7}</td>
</tr>
<tr>
<td>xxxx_value_avg</td>
<td>Average, for above (it is recommended to use this value for “routine” modelling)</td>
</tr>
<tr>
<td>xxxx_method</td>
<td>Array listing the method descriptions for each value. The nature of this array varies with the soil property under consideration, as described in the option tables for each analytical method. For example, in the case of electrical conductivity (ELCO), the method is described using sample pretreatment (e.g. sieved over 2 mm size, solution (e.g. water), ratio (e.g., 1 : 5), and ratio base (e.g. weight/volume). Details for each method are provided in the WoSIS Procedures Manual (Appendices D, E, and F in Ribeiro et al., 2018).</td>
</tr>
<tr>
<td>xxxx_date</td>
<td>Array listing the date of observation for each value</td>
</tr>
<tr>
<td>xxxx_dataset_id</td>
<td>Abbreviation for source data set (e.g. WD-ISCN)</td>
</tr>
<tr>
<td>xxxx_profile_code</td>
<td>Code for given profile in the source dataset</td>
</tr>
<tr>
<td>xxxx_license</td>
<td>Licence for given data, as indicated by the data provider (e.g. CC-BY).</td>
</tr>
<tr>
<td>(...)</td>
<td>The above “xxxx” fields are repeated for each soil property considered in Table A1.</td>
</tr>
</tbody>
</table>

<p><sup>*</sup> Name of attribute (“xxxx”) as defined under “code” in file <i>wosis_201909_attributes.tsv</i>.</p>


---


# Appendix C

## Table C1. Number of profiles by country and continent.

<table>
<thead>
<tr>
<th>Continent</th>
<th>Country name</th>
<th>ISO code</th>
<th>No. of profiles</th>
<th>Area (km<sup>2</sup>)</th>
<th>Profile density (per 1000 km<sup>2</sup>)</th>
</tr>
</thead>
<tbody>
<tr><td rowspan="48">Africa</td><td>Algeria</td><td>DZ</td><td>10</td><td>2 308 647</td><td>0.004</td></tr>
<tr><td>Angola</td><td>AO</td><td>1169</td><td>1 246 690</td><td>0.938</td></tr>
<tr><td>Benin</td><td>BJ</td><td>744</td><td>115 247</td><td>6.456</td></tr>
<tr><td>Botswana</td><td>BW</td><td>994</td><td>578 247</td><td>1.719</td></tr>
<tr><td>Burkina Faso</td><td>BF</td><td>2023</td><td>273 281</td><td>7.403</td></tr>
<tr><td>Burundi</td><td>BI</td><td>1063</td><td>26 857</td><td>39.58</td></tr>
<tr><td>Cameroon</td><td>CM</td><td>1306</td><td>465 363</td><td>2.806</td></tr>
<tr><td>Central African Republic</td><td>CF</td><td>88</td><td>619 591</td><td>0.142</td></tr>
<tr><td>Chad</td><td>TD</td><td>7</td><td>1 265 392</td><td>0.006</td></tr>
<tr><td>Côte d’Ivoire</td><td>CI</td><td>255</td><td>321 762</td><td>0.793</td></tr>
<tr><td>Democratic Republic of the Congo</td><td>CD</td><td>380</td><td>2 329 162</td><td>0.163</td></tr>
<tr><td>Egypt</td><td>EG</td><td>26</td><td>982 161</td><td>0.026</td></tr>
<tr><td>Ethiopia</td><td>ET</td><td>1712</td><td>1 129 314</td><td>1.516</td></tr>
<tr><td>Gabon</td><td>GA</td><td>47</td><td>264 022</td><td>0.178</td></tr>
<tr><td>Ghana</td><td>GH</td><td>432</td><td>238 842</td><td>1.809</td></tr>
<tr><td>Guinea</td><td>GN</td><td>128</td><td>243 023</td><td>0.527</td></tr>
<tr><td>Guinea-Bissau</td><td>GW</td><td>18</td><td>30 740</td><td>0.586</td></tr>
<tr><td>Kenya</td><td>KE</td><td>1601</td><td>582 342</td><td>2.749</td></tr>
<tr><td>Lesotho</td><td>LS</td><td>33</td><td>30 453</td><td>1.084</td></tr>
<tr><td>Liberia</td><td>LR</td><td>50</td><td>96 103</td><td>0.52</td></tr>
<tr><td>Libya</td><td>LY</td><td>14</td><td>1 620 583</td><td>0.009</td></tr>
<tr><td>Madagascar</td><td>MG</td><td>131</td><td>588 834</td><td>0.222</td></tr>
<tr><td>Malawi</td><td>MW</td><td>3049</td><td>118 715</td><td>25.683</td></tr>
<tr><td>Mali</td><td>ML</td><td>884</td><td>1 251 471</td><td>0.706</td></tr>
<tr><td>Mauritania</td><td>MR</td><td>13</td><td>1 038 527</td><td>0.013</td></tr>
<tr><td>Morocco</td><td>MA</td><td>113</td><td>414 030</td><td>0.273</td></tr>
<tr><td>Mozambique</td><td>MZ</td><td>566</td><td>787 305</td><td>0.719</td></tr>
<tr><td>Namibia</td><td>NA</td><td>1462</td><td>823 989</td><td>1.774</td></tr>
<tr><td>Niger</td><td>NE</td><td>520</td><td>1 182 602</td><td>0.44</td></tr>
<tr><td>Nigeria</td><td>NG</td><td>1402</td><td>908 978</td><td>1.542</td></tr>
<tr><td>Republic of the Congo</td><td>CG</td><td>71</td><td>340 599</td><td>0.208</td></tr>
<tr><td>Rwanda</td><td>RW</td><td>2007</td><td>25 388</td><td>79.052</td></tr>
<tr><td>Senegal</td><td>SN</td><td>312</td><td>196 200</td><td>1.59</td></tr>
<tr><td>Sierra Leone</td><td>SL</td><td>12</td><td>72 281</td><td>0.166</td></tr>
<tr><td>Somalia</td><td>SO</td><td>245</td><td>632 562</td><td>0.387</td></tr>
<tr><td>South Africa</td><td>ZA</td><td>874</td><td>1 220 127</td><td>0.716</td></tr>
<tr><td>South Sudan</td><td>SS</td><td>82</td><td>629 821</td><td>0.13</td></tr>
<tr><td>Sudan</td><td>SD</td><td>130</td><td>1 843 196</td><td>0.071</td></tr>
<tr><td>Swaziland</td><td>SZ</td><td>14</td><td>17 290</td><td>0.81</td></tr>
<tr><td>Togo</td><td>TG</td><td>9</td><td>56 767</td><td>0.159</td></tr>
<tr><td>Tunisia</td><td>TN</td><td>60</td><td>155 148</td><td>0.387</td></tr>
<tr><td>Uganda</td><td>UG</td><td>683</td><td>241 495</td><td>2.828</td></tr>
<tr><td>Tanzania</td><td>TZ</td><td>1915</td><td>939 588</td><td>2.038</td></tr>
<tr><td>Zambia</td><td>ZM</td><td>601</td><td>751 063</td><td>0.8</td></tr>
<tr><td>Zimbabwe</td><td>ZW</td><td>413</td><td>390 648</td><td>1.057</td></tr>
<tr><td>Antarctica</td><td>Antarctica</td><td>AQ</td><td>9</td><td>12 537 967</td><td>0.001</td></tr>
<tr><td rowspan="3">Asia</td><td>Afghanistan</td><td>AF</td><td>19</td><td>641 827</td><td>0.03</td></tr>
<tr><td>Armenia</td><td>AM</td><td>7</td><td>29 624</td><td>0.236</td></tr>
<tr><td>Arunachal Pradesh</td><td>*</td><td>2</td><td>67 965</td><td>0.029</td></tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Continent</th>
<th>Country name</th>
<th>ISO code</th>
<th>No. of profiles</th>
<th>Area (km<sup>2</sup>)</th>
<th>Profile density (per 1000 km<sup>2</sup>)</th>
</tr>
</thead>
<tbody>
<tr>
<td rowspan="44">Asia</td>
<td>Azerbaijan</td>
<td>AZ</td>
<td>24</td>
<td>164,780</td>
<td>0.146</td>
</tr>
<tr>
<td>Bahrain</td>
<td>BH</td>
<td>2</td>
<td>673</td>
<td>2.97</td>
</tr>
<tr>
<td>Bangladesh</td>
<td>BD</td>
<td>207</td>
<td>139,825</td>
<td>1.48</td>
</tr>
<tr>
<td>Bhutan</td>
<td>BT</td>
<td>85</td>
<td>37,674</td>
<td>2.256</td>
</tr>
<tr>
<td>Cambodia</td>
<td>KH</td>
<td>409</td>
<td>181,424</td>
<td>2.254</td>
</tr>
<tr>
<td>China</td>
<td>CN</td>
<td>1648</td>
<td>9,345,214</td>
<td>0.176</td>
</tr>
<tr>
<td>Cyprus</td>
<td>CY</td>
<td>12</td>
<td>9,249</td>
<td>1.297</td>
</tr>
<tr>
<td>Georgia</td>
<td>GE</td>
<td>17</td>
<td>69,785</td>
<td>0.244</td>
</tr>
<tr>
<td>Hong Kong</td>
<td>HK</td>
<td>2</td>
<td>1,081</td>
<td>1.851</td>
</tr>
<tr>
<td>India</td>
<td>IN</td>
<td>199</td>
<td>2,961,118</td>
<td>0.067</td>
</tr>
<tr>
<td>Indonesia</td>
<td>ID</td>
<td>180</td>
<td>1,888,620</td>
<td>0.095</td>
</tr>
<tr>
<td>Iran</td>
<td>IR</td>
<td>2010</td>
<td>1,677,319</td>
<td>1.198</td>
</tr>
<tr>
<td>Iraq</td>
<td>IQ</td>
<td>14</td>
<td>435,864</td>
<td>0.032</td>
</tr>
<tr>
<td>Israel</td>
<td>IL</td>
<td>17</td>
<td>20,720</td>
<td>0.82</td>
</tr>
<tr>
<td>Jammu and Kashmir</td>
<td>*</td>
<td>4</td>
<td>186,035</td>
<td>0.022</td>
</tr>
<tr>
<td>Japan</td>
<td>JP</td>
<td>198</td>
<td>373,651</td>
<td>0.53</td>
</tr>
<tr>
<td>Jordan</td>
<td>JO</td>
<td>47</td>
<td>89,063</td>
<td>0.528</td>
</tr>
<tr>
<td>Kazakhstan</td>
<td>KZ</td>
<td>12</td>
<td>2,841,103</td>
<td>0.004</td>
</tr>
<tr>
<td>Kuwait</td>
<td>KW</td>
<td>1</td>
<td>17,392</td>
<td>0.057</td>
</tr>
<tr>
<td>Kyrgyzstan</td>
<td>KG</td>
<td>1</td>
<td>199,188</td>
<td>0.005</td>
</tr>
<tr>
<td>Lao</td>
<td>LA</td>
<td>20</td>
<td>230,380</td>
<td>0.087</td>
</tr>
<tr>
<td>Lebanon</td>
<td>LB</td>
<td>10</td>
<td>10,136</td>
<td>0.987</td>
</tr>
<tr>
<td>Malaysia</td>
<td>MY</td>
<td>157</td>
<td>329,775</td>
<td>0.476</td>
</tr>
<tr>
<td>Mongolia</td>
<td>MN</td>
<td>9</td>
<td>1,564,529</td>
<td>0.006</td>
</tr>
<tr>
<td>Nepal</td>
<td>NP</td>
<td>142</td>
<td>147,437</td>
<td>0.963</td>
</tr>
<tr>
<td>Oman</td>
<td>OM</td>
<td>9</td>
<td>308,335</td>
<td>0.029</td>
</tr>
<tr>
<td>Pakistan</td>
<td>PK</td>
<td>45</td>
<td>788,439</td>
<td>0.057</td>
</tr>
<tr>
<td>Philippines</td>
<td>PH</td>
<td>81</td>
<td>296,031</td>
<td>0.274</td>
</tr>
<tr>
<td>South Korea</td>
<td>KR</td>
<td>23</td>
<td>99,124</td>
<td>0.232</td>
</tr>
<tr>
<td>Saudi Arabia</td>
<td>SA</td>
<td>7</td>
<td>1,925,621</td>
<td>0.004</td>
</tr>
<tr>
<td>Singapore</td>
<td>SG</td>
<td>1</td>
<td>594</td>
<td>1.683</td>
</tr>
<tr>
<td>Sri Lanka</td>
<td>LK</td>
<td>72</td>
<td>66,173</td>
<td>1.088</td>
</tr>
<tr>
<td>State of Palestine</td>
<td>PS*</td>
<td>18</td>
<td>6,225</td>
<td>2.892</td>
</tr>
<tr>
<td>Syria</td>
<td>SY</td>
<td>68</td>
<td>188,128</td>
<td>0.361</td>
</tr>
<tr>
<td>Taiwan</td>
<td>TW</td>
<td>35</td>
<td>36,127</td>
<td>0.969</td>
</tr>
<tr>
<td>Tajikistan</td>
<td>TJ</td>
<td>5</td>
<td>142,004</td>
<td>0.035</td>
</tr>
<tr>
<td>Thailand</td>
<td>TH</td>
<td>482</td>
<td>515,417</td>
<td>0.935</td>
</tr>
<tr>
<td>Turkey</td>
<td>TR</td>
<td>69</td>
<td>781,229</td>
<td>0.088</td>
</tr>
<tr>
<td>United Arab Emirates</td>
<td>AE</td>
<td>12</td>
<td>71,079</td>
<td>0.169</td>
</tr>
<tr>
<td>Uzbekistan</td>
<td>UZ</td>
<td>9</td>
<td>449,620</td>
<td>0.02</td>
</tr>
<tr>
<td>Viet Nam</td>
<td>VN</td>
<td>29</td>
<td>327,575</td>
<td>0.089</td>
</tr>
<tr>
<td>Yemen</td>
<td>YE</td>
<td>284</td>
<td>453,596</td>
<td>0.626</td>
</tr>
<tr>
<td rowspan="19">Europe</td>
<td>Albania</td>
<td>AL</td>
<td>97</td>
<td>28,682</td>
<td>3.382</td>
</tr>
<tr>
<td>Austria</td>
<td>AT</td>
<td>128</td>
<td>83,964</td>
<td>1.524</td>
</tr>
<tr>
<td>Belarus</td>
<td>BY</td>
<td>92</td>
<td>207,581</td>
<td>0.443</td>
</tr>
<tr>
<td>Belgium</td>
<td>BE</td>
<td>7009</td>
<td>30,669</td>
<td>228.536</td>
</tr>
<tr>
<td>Bosnia and Herzegovina</td>
<td>BA</td>
<td>32</td>
<td>51,145</td>
<td>0.626</td>
</tr>
<tr>
<td>Bulgaria</td>
<td>BG</td>
<td>136</td>
<td>111,300</td>
<td>1.222</td>
</tr>
<tr>
<td>Croatia</td>
<td>HR</td>
<td>78</td>
<td>56,589</td>
<td>1.378</td>
</tr>
<tr>
<td>Czech Republic</td>
<td>CZ</td>
<td>664</td>
<td>78,845</td>
<td>8.422</td>
</tr>
<tr>
<td>Denmark</td>
<td>DK</td>
<td>74</td>
<td>44,458</td>
<td>1.664</td>
</tr>
<tr>
<td>Estonia</td>
<td>EE</td>
<td>242</td>
<td>45,441</td>
<td>5.326</td>
</tr>
<tr>
<td>Finland</td>
<td>FI</td>
<td>444</td>
<td>336,892</td>
<td>1.318</td>
</tr>
<tr>
<td>France</td>
<td>FR</td>
<td>1037</td>
<td>548,785</td>
<td>1.89</td>
</tr>
<tr>
<td>Germany</td>
<td>DE</td>
<td>4345</td>
<td>357,227</td>
<td>12.163</td>
</tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Continent</th>
<th>Country name</th>
<th>ISO code</th>
<th>No. of profiles</th>
<th>Area (km<sup>2</sup>)</th>
<th>Profile density (per 1000 km<sup>2</sup>)</th>
</tr>
</thead>
<tbody>
<tr>
<td rowspan="27">Europe</td>
<td>Greece</td>
<td>GR</td>
<td>370</td>
<td>132 549</td>
<td>2.791</td>
</tr>
<tr>
<td>Hungary</td>
<td>HU</td>
<td>1420</td>
<td>93 119</td>
<td>15.249</td>
</tr>
<tr>
<td>Iceland</td>
<td>IS</td>
<td>11</td>
<td>102 566</td>
<td>0.107</td>
</tr>
<tr>
<td>Ireland</td>
<td>IE</td>
<td>125</td>
<td>69 809</td>
<td>1.791</td>
</tr>
<tr>
<td>Italy</td>
<td>IT</td>
<td>575</td>
<td>301 651</td>
<td>1.906</td>
</tr>
<tr>
<td>Latvia</td>
<td>LV</td>
<td>102</td>
<td>64 563</td>
<td>1.58</td>
</tr>
<tr>
<td>Lithuania</td>
<td>LT</td>
<td>127</td>
<td>64 943</td>
<td>1.956</td>
</tr>
<tr>
<td>Luxembourg</td>
<td>LU</td>
<td>141</td>
<td>2621</td>
<td>53.802</td>
</tr>
<tr>
<td>Montenegro</td>
<td>ME</td>
<td>12</td>
<td>13 776</td>
<td>0.871</td>
</tr>
<tr>
<td>Netherlands</td>
<td>NL</td>
<td>320</td>
<td>35 203</td>
<td>9.09</td>
</tr>
<tr>
<td>North Macedonia</td>
<td>MK</td>
<td>20</td>
<td>25 424</td>
<td>0.787</td>
</tr>
<tr>
<td>Norway</td>
<td>NO</td>
<td>507</td>
<td>324 257</td>
<td>1.564</td>
</tr>
<tr>
<td>Poland</td>
<td>PL</td>
<td>618</td>
<td>311 961</td>
<td>1.981</td>
</tr>
<tr>
<td>Portugal</td>
<td>PT</td>
<td>460</td>
<td>91 876</td>
<td>5.007</td>
</tr>
<tr>
<td>Moldova</td>
<td>MD</td>
<td>35</td>
<td>33 798</td>
<td>1.036</td>
</tr>
<tr>
<td>Romania</td>
<td>RO</td>
<td>104</td>
<td>238 118</td>
<td>0.437</td>
</tr>
<tr>
<td>Russian Federation</td>
<td>RU</td>
<td>1410</td>
<td>16 998 830</td>
<td>0.083</td>
</tr>
<tr>
<td>Serbia</td>
<td>RS</td>
<td>69</td>
<td>88 478</td>
<td>0.78</td>
</tr>
<tr>
<td>Slovakia</td>
<td>SK</td>
<td>161</td>
<td>49 072</td>
<td>3.281</td>
</tr>
<tr>
<td>Slovenia</td>
<td>SI</td>
<td>67</td>
<td>20 320</td>
<td>3.297</td>
</tr>
<tr>
<td>Spain</td>
<td>ES</td>
<td>905</td>
<td>505 752</td>
<td>1.789</td>
</tr>
<tr>
<td>Svalbard and Jan Mayen Islands</td>
<td>SJ</td>
<td>4</td>
<td>63 464</td>
<td>0.063</td>
</tr>
<tr>
<td>Sweden</td>
<td>SE</td>
<td>583</td>
<td>449 212</td>
<td>1.298</td>
</tr>
<tr>
<td>Switzerland</td>
<td>CH</td>
<td>10 943</td>
<td>41 257</td>
<td>265.238</td>
</tr>
<tr>
<td>Ukraine</td>
<td>UA</td>
<td>409</td>
<td>600 526</td>
<td>0.681</td>
</tr>
<tr>
<td>United Kingdom</td>
<td>GB</td>
<td>1435</td>
<td>244 308</td>
<td>5.874</td>
</tr>
<tr>
<td rowspan="26">North America</td>
<td>Barbados</td>
<td>BB</td>
<td>3</td>
<td>433</td>
<td>6.928</td>
</tr>
<tr>
<td>Belize</td>
<td>BZ</td>
<td>29</td>
<td>21 764</td>
<td>1.332</td>
</tr>
<tr>
<td>Canada</td>
<td>CA</td>
<td>8516</td>
<td>9 875 646</td>
<td>0.862</td>
</tr>
<tr>
<td>Costa Rica</td>
<td>CR</td>
<td>560</td>
<td>51 042</td>
<td>10.971</td>
</tr>
<tr>
<td>Cuba</td>
<td>CU</td>
<td>53</td>
<td>110 863</td>
<td>0.478</td>
</tr>
<tr>
<td>Dominican Republic</td>
<td>DO</td>
<td>10</td>
<td>48 099</td>
<td>0.208</td>
</tr>
<tr>
<td>El Salvador</td>
<td>SV</td>
<td>38</td>
<td>20 732</td>
<td>1.833</td>
</tr>
<tr>
<td>Greenland</td>
<td>GL</td>
<td>6</td>
<td>2 165 159</td>
<td>0.003</td>
</tr>
<tr>
<td>Guadeloupe</td>
<td>GP</td>
<td>5</td>
<td>1697</td>
<td>2.947</td>
</tr>
<tr>
<td>Guatemala</td>
<td>GT</td>
<td>27</td>
<td>109 062</td>
<td>0.248</td>
</tr>
<tr>
<td>Honduras</td>
<td>HN</td>
<td>38</td>
<td>112 124</td>
<td>0.339</td>
</tr>
<tr>
<td>Jamaica</td>
<td>JM</td>
<td>76</td>
<td>10 965</td>
<td>6.931</td>
</tr>
<tr>
<td>Mexico</td>
<td>MX</td>
<td>7554</td>
<td>1 949 527</td>
<td>3.875</td>
</tr>
<tr>
<td>Netherlands Antilles</td>
<td>AN</td>
<td>4</td>
<td>790</td>
<td>5.066</td>
</tr>
<tr>
<td>Nicaragua</td>
<td>NI</td>
<td>26</td>
<td>128 376</td>
<td>0.203</td>
</tr>
<tr>
<td>Panama</td>
<td>PA</td>
<td>51</td>
<td>74 850</td>
<td>0.681</td>
</tr>
<tr>
<td>Puerto Rico</td>
<td>PR</td>
<td>280</td>
<td>8937</td>
<td>31.329</td>
</tr>
<tr>
<td>Trinidad and Tobago</td>
<td>TT</td>
<td>2</td>
<td>5144</td>
<td>0.389</td>
</tr>
<tr>
<td>United States of America</td>
<td>US</td>
<td>56 277</td>
<td>9 315 946</td>
<td>6.041</td>
</tr>
<tr>
<td>United States Virgin Islands</td>
<td>VI</td>
<td>49</td>
<td>352</td>
<td>139.069</td>
</tr>
<tr>
<td rowspan="8">Oceania</td>
<td>Australia</td>
<td>AU</td>
<td>42 758</td>
<td>7 687 634</td>
<td>5.562</td>
</tr>
<tr>
<td>Cook Islands</td>
<td>CK</td>
<td>1</td>
<td>241</td>
<td>4.142</td>
</tr>
<tr>
<td>Fiji</td>
<td>FJ</td>
<td>9</td>
<td>18 293</td>
<td>0.492</td>
</tr>
<tr>
<td>Guam</td>
<td>GU</td>
<td>15</td>
<td>544</td>
<td>27.579</td>
</tr>
<tr>
<td>Micronesia (Federated States of)</td>
<td>FM</td>
<td>78</td>
<td>740</td>
<td>105.397</td>
</tr>
<tr>
<td>New Caledonia</td>
<td>NC</td>
<td>2</td>
<td>18 574</td>
<td>0.108</td>
</tr>
<tr>
<td>New Zealand</td>
<td>NZ</td>
<td>53</td>
<td>270 415</td>
<td>0.196</td>
</tr>
<tr>
<td>Palau</td>
<td>PW</td>
<td>18</td>
<td>451</td>
<td>39.924</td>
</tr>
</tbody>
</table>



---



<table>
<thead>
<tr>
<th>Continent</th>
<th>Country name</th>
<th>ISO code</th>
<th>No. of profiles</th>
<th>Area (km<sup>2</sup>)</th>
<th>Profile density (per 1000 km<sup>2</sup>)</th>
</tr>
</thead>
<tbody>
<tr>
<td rowspan="4"></td>
<td>Papua New Guinea</td>
<td>PG</td>
<td>31</td>
<td>462 230</td>
<td>0.067</td>
</tr>
<tr>
<td>Samoa</td>
<td>WS</td>
<td>17</td>
<td>2 835</td>
<td>5.996</td>
</tr>
<tr>
<td>Solomon Islands</td>
<td>SB</td>
<td>1</td>
<td>28 264</td>
<td>0.035</td>
</tr>
<tr>
<td>Vanuatu</td>
<td>VU</td>
<td>1</td>
<td>12 236</td>
<td>0.082</td>
</tr>
<tr>
<td rowspan="14">South America</td>
<td>Argentina</td>
<td>AR</td>
<td>244</td>
<td>2 780 175</td>
<td>0.088</td>
</tr>
<tr>
<td>Bolivia</td>
<td>BO</td>
<td>86</td>
<td>1 084 491</td>
<td>0.079</td>
</tr>
<tr>
<td>Brazil</td>
<td>BR</td>
<td>8883</td>
<td>8 485 946</td>
<td>1.047</td>
</tr>
<tr>
<td>Chile</td>
<td>CL</td>
<td>72</td>
<td>753 355</td>
<td>0.096</td>
</tr>
<tr>
<td>Colombia</td>
<td>CO</td>
<td>237</td>
<td>1 137 939</td>
<td>0.208</td>
</tr>
<tr>
<td>Ecuador</td>
<td>EC</td>
<td>94</td>
<td>256 249</td>
<td>0.367</td>
</tr>
<tr>
<td>French Guiana</td>
<td>GF</td>
<td>30</td>
<td>83 295</td>
<td>0.36</td>
</tr>
<tr>
<td>Guyana</td>
<td>GY</td>
<td>43</td>
<td>211 722</td>
<td>0.203</td>
</tr>
<tr>
<td>Paraguay</td>
<td>PY</td>
<td>1</td>
<td>399 349</td>
<td>0.003</td>
</tr>
<tr>
<td>Peru</td>
<td>PE</td>
<td>159</td>
<td>1 290 640</td>
<td>0.123</td>
</tr>
<tr>
<td>Suriname</td>
<td>SR</td>
<td>31</td>
<td>145 100</td>
<td>0.214</td>
</tr>
<tr>
<td>Uruguay</td>
<td>UY</td>
<td>132</td>
<td>177 811</td>
<td>0.742</td>
</tr>
<tr>
<td>Venezuela</td>
<td>VE</td>
<td>206</td>
<td>912 025</td>
<td>0.226</td>
</tr>
</tbody>
</table>

> *Disputed territories. Country names and areas are based on the Global Administrative Layers (GAUL) database; see http://www.fao.org/geonetwork/srv/en/metadata.show?id=12691 (last access: 8 January 2020).



---


# Appendix D: Distribution of soil profiles by eco-region and by biome

<table>
<thead>
<tr>
<th colspan="3">Table D1. Number of soil profiles by broad rainfall and temperature zone∗.</th>
</tr>
<tr>
<th>Bioclimate</th>
<th colspan="2">Profiles</th>
</tr>
<tr>
<th></th>
<th>n</th>
<th>%</th>
</tr>
</thead>
<tbody>
<tr>
<td>Arctic</td>
<td>2</td>
<td>0.00</td>
</tr>
<tr>
<td>Very cold:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Dry</td>
<td>6</td>
<td>0.00</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>139</td>
<td>0.07</td>
</tr>
<tr>
<td>– Moist</td>
<td>366</td>
<td>0.19</td>
</tr>
<tr>
<td>– Wet</td>
<td>1839</td>
<td>0.94</td>
</tr>
<tr>
<td>– Very wet</td>
<td>949</td>
<td>0.48</td>
</tr>
<tr>
<td>Cold:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Dry</td>
<td>9</td>
<td>0.00</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>537</td>
<td>0.27</td>
</tr>
<tr>
<td>– Moist</td>
<td>2048</td>
<td>1.04</td>
</tr>
<tr>
<td>– Wet</td>
<td>10 921</td>
<td>5.56</td>
</tr>
<tr>
<td>– Very wet</td>
<td>5871</td>
<td>2.99</td>
</tr>
<tr>
<td>Cool:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Very dry</td>
<td>9</td>
<td>0.00</td>
</tr>
<tr>
<td>– Dry</td>
<td>217</td>
<td>0.11</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>7098</td>
<td>3.61</td>
</tr>
<tr>
<td>– Moist</td>
<td>4308</td>
<td>2.19</td>
</tr>
<tr>
<td>– Wet</td>
<td>32 927</td>
<td>16.76</td>
</tr>
<tr>
<td>– Very wet</td>
<td>6186</td>
<td>3.15</td>
</tr>
<tr>
<td>Warm:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Very dry</td>
<td>25</td>
<td>0.01</td>
</tr>
<tr>
<td>– Dry</td>
<td>1007</td>
<td>0.51</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>14 778</td>
<td>7.52</td>
</tr>
<tr>
<td>– Moist</td>
<td>6860</td>
<td>3.49</td>
</tr>
<tr>
<td>– Wet</td>
<td>28 595</td>
<td>14.55</td>
</tr>
<tr>
<td>– Very wet</td>
<td>853</td>
<td>0.43</td>
</tr>
<tr>
<td>Hot:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Very dry</td>
<td>40</td>
<td>0.02</td>
</tr>
<tr>
<td>– Dry</td>
<td>2047</td>
<td>1.04</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>14 774</td>
<td>7.52</td>
</tr>
<tr>
<td>– Moist</td>
<td>5783</td>
<td>2.94</td>
</tr>
<tr>
<td>– Wet</td>
<td>18 646</td>
<td>9.49</td>
</tr>
<tr>
<td>– Very wet</td>
<td>2411</td>
<td>1.23</td>
</tr>
<tr>
<td>Very hot:</td>
<td></td>
<td></td>
</tr>
<tr>
<td>– Very dry</td>
<td>20</td>
<td>0.01</td>
</tr>
<tr>
<td>– Dry</td>
<td>566</td>
<td>0.29</td>
</tr>
<tr>
<td>– Semi-dry</td>
<td>7727</td>
<td>3.93</td>
</tr>
<tr>
<td>– Moist</td>
<td>4935</td>
<td>2.51</td>
</tr>
<tr>
<td>– Wet</td>
<td>8895</td>
<td>4.53</td>
</tr>
<tr>
<td>– Very wet</td>
<td>3199</td>
<td>1.63</td>
</tr>
<tr>
<td>No data</td>
<td>1905</td>
<td>0.97</td>
</tr>
</tbody>
</table>

<p>∗ Bioclimatic (rainfall and temperature) zones as defined by Sayre et al. (2014).</p>

<table>
<thead>
<tr>
<th colspan="3">Table D2. Number of soil profiles by biome∗.</th>
</tr>
<tr>
<th>Biome</th>
<th colspan="2">Soil profiles</th>
</tr>
<tr>
<th></th>
<th>n</th>
<th>%</th>
</tr>
</thead>
<tbody>
<tr>
<td>Boreal forests/taiga</td>
<td>6129</td>
<td>3.1</td>
</tr>
<tr>
<td>Deserts and xeric shrublands</td>
<td>10 212</td>
<td>5.2</td>
</tr>
<tr>
<td>Flooded grasslands and savannas</td>
<td>779</td>
<td>0.4</td>
</tr>
<tr>
<td>Mangroves</td>
<td>682</td>
<td>0.3</td>
</tr>
<tr>
<td>Mediterranean forests, woodlands and scrub</td>
<td>16 759</td>
<td>8.5</td>
</tr>
<tr>
<td>Montane grasslands and shrublands</td>
<td>1402</td>
<td>0.7</td>
</tr>
<tr>
<td>Temperate broadleaf and mixed forests</td>
<td>63 912</td>
<td>32.5</td>
</tr>
<tr>
<td>Temperate conifer forests</td>
<td>12 153</td>
<td>6.2</td>
</tr>
<tr>
<td>Temperate grasslands, savannas and shrublands</td>
<td>25 357</td>
<td>12.9</td>
</tr>
<tr>
<td>Tropical and subtropical coniferous forests</td>
<td>1354</td>
<td>0.7</td>
</tr>
<tr>
<td>Tropical and subtropical dry broadleaf forests</td>
<td>3808</td>
<td>1.9</td>
</tr>
<tr>
<td>Tropical and subtropical grasslands, savannas and shrublands</td>
<td>34 779</td>
<td>17.7</td>
</tr>
<tr>
<td>Tropical and subtropical moist broadleaf forests</td>
<td>16 492</td>
<td>8.4</td>
</tr>
<tr>
<td>Tundra</td>
<td>1977</td>
<td>1.0</td>
</tr>
<tr>
<td>No data</td>
<td>703</td>
<td>0.4</td>
</tr>
</tbody>
</table>

<p>∗ Biomes defined according to “Terrestrial Ecoregions of the World” (TEOW) (D. M. Olson et al., 2001).</p>


---


# Author contributions
NB led the DATA (WoSIS) project and wrote the body of the paper. ER provided special expertise on database management and AO on soil analytical methods. All co-authors contributed to the writing and revision of this paper.

# Competing interests
The authors declare that they have no conflict of interest.

# Acknowledgements
The development of WoSIS has been made possible thanks to the contributions and shared knowledge of a steadily growing number of data providers, including soil survey organisations, research institutes and individual experts, for which we are grateful; for an overview, please see https://www.isric.org/explore/wosis/wosis-contributing-institutions-and-experts (last access: 8 January 2020). We thank our colleagues Laura Poggio, Luis de Sousa and Bas Kempen for their constructive comments on a “pre-release” of the snapshot data. Further, the manuscript benefitted from the constructive comments provided by the two reviewers.

# Financial support
ISRIC – World Soil Information, legally registered as the International Soil Reference and Information Centre, receives core funding from the Dutch Government.

# Review statement
This paper was edited by David Carlson and reviewed by Alessandro Samuel-Rosa and one anonymous referee.

# References

Al-Shammary, A. A. G., Kouzani, A. Z., Kaynak, A., Khoo, S. Y., Norton, M., and Gates, W.: Soil Bulk Density Estimation Methods: A Review, Pedosphere, 28, 581–596, https://doi.org/10.1016/S1002-0160(18)60034-7, 2018.

Arrouays, D., Leenaars, J. G. B., Richer-de-Forges, A. C., Adhikari, K., Ballabio, C., Greve, M., Grundy, M., Guerrero, E., Hempel, J., Hengl, T., Heuvelink, G., Batjes, N., Carvalho, E., Hartemink, A., Hewitt, A., Hong, S.-Y., Krasilnikov, P., Lagacherie, P., Lelyk, G., Libohova, Z., Lilly, A., McBratney, A., McKenzie, N., Vasquez, G. M., Mulder, V. L., Minasny, B., Montanarella, L., Odeh, I., Padarian, J., Poggio, L., Roudier, P., Saby, N., Savin, I., Searle, R., Solbovoy, V., Thompson, J., Smith, S., Sulaeman, Y., Vintila, R., Rossel, R. V., Wilson, P., Zhang, G.-L., Swerts, M., Oorts, K., Karklins, A., Feng, L., Ibelles Navarro, A. R., Levin, A., Laktionova, T., Dell’Acqua, M., Suvannang, N., Ruam, W., Prasad, J., Patil, N., Husnjak, S., Pásztor, L., Okx, J., Hallet, S., Keay, C., Farewell, T., Lilja, H., Juilleret, J., Marx, S., Takata, Y., Kazuyuki, Y., Mansuy, N., Panagos, P., Van Liedekerke, M., Skalsky, R., Sobocka, J., Kobza, J., Eftekhari, K., Alavipanah, S. K., Moussadek, R., Badraoui, M., Da Silva, M., Paterson, G., Gonçalves, M. d. C., Theocharopoulos, S., Yemefack, M., Tedou, S., Vrscaj, B., Grob, U., Kozák, J., Boruvka, L., Dobos, E., Taboada, M., Moretti, L., and Rodriguez, D.: Soil legacy data rescue via GlobalSoilMap and other international and national initiatives, GeoResJ, 14, 1–19, https://doi.org/10.1016/j.grj.2017.06.001, 2017.

Baroni, G., Zink, M., Kumar, R., Samaniego, L., and Attinger, S.: Effects of uncertainty in soil properties on simulated hydrological states and fluxes at different spatio-temporal scales, Hydrol. Earth Syst. Sci., 21, 2301–2320, https://doi.org/10.5194/hess-21-2301-2017, 2017.

Baritz, R., Erdogan, H., Fujii, K., Takata, Y., Nocita, M., Bussian, B., Batjes, N. H., Hempel, J., Wilson, P., and Vargas, R.: Harmonization of methods, measurements and indicators for the sustainable management and protection of soil resources (Providing mechanisms for the collation, analysis and exchange of consistent and comparable global soil data and information), Global Soil Partnership, FAO, 44 pp., 2014.

Batjes, N. H.: Harmonized soil profile data for applications at global and continental scales: updates to the WISE database, Soil Use Manage., 25, 124–127 https://doi.org/10.1111/j.1475-2743.2009.00202.x, (supplemental information: https://www.isric.org/sites/default/files/isric_report_2008_02.pdf, last access: 8 January 2020), 2009.

Batjes, N. H.: Harmonised soil property values for broad-scale modelling (WISE30sec) with estimates of global soil carbon stocks, Geoderma, 269, 61–68, https://doi.org/10.1016/j.geoderma.2016.01.034, 2016.

Batjes, N. H., Ribeiro, E., van Oostrum, A., Leenaars, J., Hengl, T., and Mendes de Jesus, J.: WoSIS: providing standardised soil profile data for the world, Earth Syst. Sci. Data, 9, 1–14, https://doi.org/10.5194/essd-9-1-2017, 2017.

Batjes, N. H., Ribeiro, E., and van Oostrum, A. J. M.: Standardised soil profile data for the world (WoSIS snapshot – September 2019), ISRIC WDC-Soils, https://doi.org/10.17027/isric-wdcsoils.20190901, 2019.

Bridges, E. M.: Soil horizon designations: past use and future prospects, Catena, 20, 363–373, https://doi.org/10.1016/S0341-8162(05)80002-5, 1993.

Cressie, N. and Kornak, J.: Spatial Statistics in the Presence of Location Error with an Application to Remote Sensing of the Environment, Stat. Sci., 18, 436–456, https://projecteuclid.org:443/euclid.ss/1081443228, 2003.

Dai, Y., Shangguan, W., Wei, N., Xin, Q., Yuan, H., Zhang, S., Liu, S., Lu, X., Wang, D., and Yan, F.: A review of the global soil property maps for Earth system models, SOIL, 5, 137–158, https://doi.org/10.5194/soil-5-137-2019, 2019.

FAO: Guidelines for the description of soils, FAO, Rome, 1977.

FAO: Guidelines for soil description (4th Edn.), FAO, Rome, 97 pp., 2006.

FAO, IIASA, ISRIC, ISSCAS, and JRC: Harmonized World Soil Database (version 1.2), prepared by: Nachtergaele, F. O., van Velthuizen, H., Verelst, L., Wiberg, D., Batjes, N. H., Dijkshoorn, J. A., van Engelen, V. W. P., Fischer, G., Jones, A., Montanarella, L., Petri, M., Prieler, S., Teixeira, E., and Shi, X., Food and Agriculture Organization of the United Nations (FAO), International Institute for Applied Systems Analysis (IIASA), ISRIC – World Soil Information, Institute of Soil Science – Chinese Academy of Sciences (ISSCAS), Joint Research Centre of the European Commission (JRC), Laxenburg, Austria, 2012.

FAO-ISRIC: Guidelines for soil description, 3rd Edn., Rev., FAO, Rome, 70 pp., 1986.

Finke, P.: Quality assessment of digital soil maps: producers and users perspectives, in: Digital soil mapping: An introductory per-


---


* Lagacherie, P., McBratney, A., and Voltz, M. (eds.): Elsevier, Amsterdam, 523–541, 2006.
* Folberth, C., Skalsky, R., Moltchanova, E., Balkovic, J., Azevedo, L. B., Obersteiner, M., and van der Velde, M.: Uncertainty in soil data can outweigh climate impact signals in global crop yield simulations, Nat. Commun., 7, 11872, https://doi.org/10.1038/ncomms11872, 2016.
* Gerasimova, M. I., Lebedeva, I. I., and Khitrov, N. B.: Soil horizon designation: State of the art, problems, and proposals, Eurasian Soil Sci., 46, 599–609, https://doi.org/10.1134/S1064229313050037, 2013.
* GlobalSoilMap: Specifications Tiered GlobalSoilMap products (Release 2.4), 52 pp., 2015.
* Grimm, R. and Behrens, T.: Uncertainty analysis of sample locations within digital soil mapping approaches, Geoderma, 155, 154–163, https://doi.org/10.1016/j.geoderma.2009.05.006, 2010.
* GSP Pillar 4 Working Group: Towards the implementation of GloSIS through a Country Soil Information Systems (CountrySIS) Framework (Concept Note, draft), available at: http://www.fao.org/global-soil-partnership/pillars-action/4-information-data/glosis/en/, last access: 26 November 2018.
* Guevara, M., Olmedo, G. F., Stell, E., Yigini, Y., Aguilar Duarte, Y., Arellano Hernández, C., Arévalo, G. E., Arroyo-Cruz, C. E., Bolivar, A., Bunning, S., Bustamante Cañas, N., Cruz-Gaistardo, C. O., Davila, F., Dell Acqua, M., Encina, A., Figueredo Tacona, H., Fontes, F., Hernández Herrera, J. A., Ibelles Navarro, A. R., Loayza, V., Manueles, A. M., Mendoza Jara, F., Olivera, C., Osorio Hermosilla, R., Pereira, G., Prieto, P., Ramos, I. A., Rey Brina, J. C., Rivera, R., Rodríguez-Rodríguez, J., Roopnarine, R., Rosales Ibarra, A., Rosales Riveiro, K. A., Schulz, G. A., Spence, A., Vasques, G. M., Vargas, R. R., and Vargas, R.: No silver bullet for digital soil mapping: country-specific soil organic carbon estimates across Latin America, SOIL, 4, 173–193, https://doi.org/10.5194/soil-4-173-2018, 2018.
* Hendriks, C. M. J., Stoorvogel, J. J., and Claessens, L.: Exploring the challenges with soil data in regional land use analysis, Agr. Syst., 144, 9–21, https://doi.org/10.1016/j.agsy.2016.01.007, 2016.
* Hengl, T., Leenaars, J. G. B., Shepherd, K. D., Walsh, M. G., Heuvelink, G. B. M., Mamo, T., Tilahun, H., Berkhout, E., Cooper, M., Fegraus, E., Wheeler, I., and Kwabena, N. A.: Soil nutrient maps of Sub-Saharan Africa: assessment of soil nutrient content at 250 m spatial resolution using machine learning, Nutr. Cycl. Agroecosys., 109, 77–102, https://doi.org/10.1007/s10705-017-9870-x, 2017a.
* Hengl, T., Mendes de Jesus, J., Heuvelink, G. B. M., Ruiperez Gonzalez, M., Kilibarda, M., Blagotić, A., Shangguan, W., Wright, M. N., Geng, X., Bauer-Marschallinger, B., Guevara, M. A., Vargas, R., MacMillan, R. A., Batjes, N. H., Leenaars, J. G. B., Ribeiro, E., Wheeler, I., Mantel, S., and Kempen, B.: SoilGrids250m: Global gridded soil information based on machine learning, PLoS ONE, 12, e0169748, https://doi.org/10.1371/journal.pone.0169748, 2017b.
* Heuvelink, G. B. M.: Uncertainty quantification of GlobalSoilMap products in: GlobalSoilMap. Basis of the Global Spatial Soil Information System, edited by: Arrouays, D., McKenzie, N., Hempel, J., Forges, A. R. D., and McBratney, A., Taylor & Francis Group, London, UK, 335–240, 2014.
* Heuvelink, G. B. M. and Brown, J. D.: Towards a soil information system for uncertain soil data in: Digital soil mapping: An introductory perspective, edited by: Lagacherie, P., McBratney, A., and Voltz, M., Elsevier, Amsterdam, 97–106, 2006.
* INSPIRE: Data specifications – Infrastructure for spatial information in the European Community, available at: http://inspire.ec.europa.eu/index.cfm/pageid/2 (last access: 25 April 2016), 2015.
* ISO-28258: Soil quality – Digital exchange of soil-related data, available at: https://www.iso.org/obp/ui#iso:std:iso:28258:ed-1:v1:en (last access: 31 January 2018), 2013.
* ISRIC: Data and Software Policy: available at: http://www.isric.org/sites/default/files/ISRIC_Data_Policy_2016jun21.pdf (last accesss: 15 May 2019), 2016.
* IUSS Working Group WRB: World Reference Base for Soil Resources (2nd Edn.), FAO, Rome, World Soil Resources Report 103, 145 pp., 2006.
* IUSS Working Group WRB: World Reference Base for soil resources 2014 – International soil classification system for naming soils and creating legends for soil maps (update 2015), Global Soil Partnership, International Union of Soil Sciences, and Food and Agriculture Organization of the United Nations, Rome, World Soil Resources Reports 106, 182 pp., 2015.
* Kalra, Y. P. and Maynard, D. G.: Methods manual for forest soil and plant analysis, Forestry Canada, Edmonton (Alberta), 116 pp., 1991.
* Leenaars, J. G. B., van Oostrum, A. J. M., and Ruiperez Gonzalez, M.: Africa Soil Profiles Database: A compilation of georeferenced and standardised legacy soil profile data for Sub Saharan Africa (version 1.2), Africa Soil Information Service (AfSIS) and ISRIC – World Soil Information, Wageningen, Report 2014/01, 160 pp., 2014.
* Leenaars, J. G. B., Claessens, L., Heuvelink, G. B. M., Hengl, T., Ruiperez González, M., van Bussel, L. G. J., Guilpart, N., Yang, H., and Cassman, K. G.: Mapping rootable depth and root zone plant-available water holding capacity of the soil of sub-Saharan Africa, Geoderma, 324, 18–36, https://doi.org/10.1016/j.geoderma.2018.02.046, 2018.
* Lutz, F., Stoorvogel, J. J., and Müller, C.: Options to model the effects of tillage on N₂O emissions at the global scale, Ecol. Model., 392, 212–225, 2019.
* Magnusson, B. and Örnemark, U.: The Fitness for Purpose of Analytical Methods – A Laboratory Guide to Method Validation and Related Topics (2nd Edn.), available at: http://www.eurachem.org (last access: 8 September 2019), 2014.
* Maire, V., Wright, I. J., Prentice, I. C., Batjes, N. H., Bhaskar, R., van Bodegom, P. M., Cornwell, W. K., Ellsworth, D., Niinemets, Ü., Ordonez, A., Reich, P. B., and Santiago, L. S.: Global effects of soil and climate on leaf photosynthetic traits and rates, Global Ecol. Biogeogr., 24, 706–715, https://doi.org/10.1111/geb.12296, 2015.
* Malhotra, A., Todd-Brown, K., Nave, L. E., Batjes, N. H., Holmquist, J. R., Hoyt, A. M., Iversen, C. M., Jackson, R. B., Lajtha, K., Lawrence, C., Vindušková, O., Wieder, W., Williams, M., Hugelius, G., and Harden, J.: The landscape of soil carbon data: emerging questions, synergies and databases, Prog. Phys. Geog., 43, 707–719, https://doi.org/10.1177/0309133319873309, 2019.
* Moulatlet, G. M., Zuquim, G., Figueiredo, F. O. G., Lehtonen, S., Emilio, T., Ruokolainen, K., and Tuomisto, H.: Using dig-


---


ital soil maps to infer edaphic affinities of plant species in Amazonia: Problems and prospects, Ecol. Evol., 7, 8463–8477, https://doi.org/10.1002/ece3.3242, 2017.  
Munzert, M., Kießling, G., Übelhör, W., Nätscher, L., and Neubert, K.-H.: Expanded measurement uncertainty of soil parameters derived from proficiency-testing data, J. Plant Nutr. Soil Sci., 170, 722–728, https://doi.org/10.1002/jpln.200620701, 2007.  
Nave, L., Johnson, K., van Ingen, C., Agarwal, D., Humphrey, M., and Beekwilder, N.: ISCN Database V3-1, https://doi.org/10.17040/ISCN/1305039, 2017.  
OGC: Soil Data IE (Interoperability Experiment), available at: https://www.opengeospatial.org/projects/initiatives/soildataie, last access: 14 June 2019.  
Olson, D. M., Dinerstein, E., Wikramanayake, E. D., Burgess, N. D., Powell, G. V. N., Underwood, E. C., D’amico, J. A., Itoua, I., Strand, H. E., Morrison, J. C., Loucks, C. J., Allnutt, T. F., Ricketts, T. H., Kura, Y., Lamoreux, J. F., Wettengel, W. W., Hedao, P., and Kassem, K. R.: Terrestrial Ecoregions of the World: A New Map of Life on Earth: A new global map of terrestrial ecoregions provides an innovative tool for conserving biodiversity, BioScience, 51, 933–938, https://doi.org/10.1641/0006-3568(2001)051[0933:TEOTWA]2.0.CO;2, 2001.  
Olson, R. J., Johnson, K. R., Zheng, D. L., and Scurlock, J. M. O.: Global and regional ecosystem modelling: databases of model drivers and validation measurements, Oak Ridge National Laboratory, Oak Ridge, ORNL/TM-2001/196, 95 pp., 2001.  
Orgiazzi, A., Ballabio, C., Panagos, P., Jones, A., and Fernández-Ugalde, O.: LUCAS Soil, the largest expandable soil dataset for Europe: a review, Eur. J. Soil Sci., 69, 140–153, https://doi.org/10.1111/ejss.12499, 2018.  
Rayment, E. R. and Lyons, D. J.: Soil chemical methods – Australasia, CSIRO Publishing, 495 pp., 2011.  
Ribeiro, E., Batjes, N. H., and Van Oostrum, A. J. M.: World Soil Information Service (WoSIS) – Towards the standardization and harmonization of world soil data, Procedures Manual 2018, ISRIC – World Soil Information, Wageningen, ISRIC Report 2018/01, 166 pp., 2018.  
Rossel, R. A. V. and McBratney, A. B.: Soil chemical analytical accuracy and costs: implications from precision agriculture, Aust. J. Exp. Agr., 38, 765–775, https://doi.org/10.1071/EA97158, 1998.  
Rossiter, D.: Accessing WoSIS from R – “Snapshot” version, available at: https://www.isric.org/sites/default/files/WoSIS_Snapshot_With_R_2.pdf (last access: 30 January 2020), 2019.  
Sanderman, J., Hengl, T., and Fiske, G. J.: Soil carbon debt of 12,000 years of human land use, P. Natl. Acad. Sci. USA, 36, 9575–9580, https://doi.org/10.1073/pnas.1706103114, 2017.  
Sayre, R., Dangermond, J., Frye, C., Vaughan, R., Aniello, P., Breyer, S., Cribbs, D., Hopkins, D., Nauman, R., Derrenbacher, W., Burton, D., Grosse, A., True, D., Metzger, M., Hartmann, J., Moosdorf, N., Dürr, H., Paganini, M., DeFourny, P., Arino, O., and Maynard, S.: A New Map of Global Ecological Land Units – An Ecophysiographic Stratification Approach, Association of American Geographers, Washington DC, 46 pp., 2014.  
Schoeneberger, P. J., Wysocki, D. A., Benham, E. C., and Soil Survey Staff: Field book for describing and sampling soils (ver. 3.0), National Soil Survey Center Natural Resources Conservation Service, U.S. Department of Agriculture, Lincoln (NE), 2012.  
Soil Survey Staff: Soil Survey Laboratory Information Manual (Ver. 2.0), National Soil Survey Center, Soil Survey Laboratory, USDA-NRCS, Lincoln (NE), Soil Survey Investigation Report No. 45, 506 pp., 2011.  
Soil Survey Staff: Kellogg Soil Survey Laboratory Methods Manual, Version 5.0, edited by: Burt, R. and Soil Survey Staff, U.S. Department of Agriculture, Natural Resources Conservation Service, Lincoln (Nebraska), 1001 pp., 2014a.  
Soil Survey Staff: Keys to Soil Taxonomy, 12th Edn., USDA-Natural Resources Conservation Service, Washington, DC, 2014b.  
Suvannang, N., Hartmann, C., Yakimenko, O., Solokha, M., Bertsch, F., and Moody, P.: Evaluation of the First Global Soil Laboratory Network (GLOSOLAN) online survey for assessing soil laboratory capacities, Global Soil Partnership (GSP)/Food and Agriculture Organization of the United Nations (FAO), Rome, GLOSOLAN/18/Survey Report, 54 pp., 2018.  
Terhoeven-Urselmans, T., Shepherd, K. D., Chabrillat, S., and Ben-Dor, E.: Application of a global soil spectral library as tool for soil quality assessment in Sub-Saharan Africa, A EUFAR Workshop on Quantitative Applications of Soil Spectroscopy, p. 15, 5–16 April 2010.  
Tóth, G., Jones, A., and Montanarella, L.: LUCAS Topsoil survey: methodology, data and results Land Resource Management Unit – Soil Action, European Commission Joint Research Centre Institute for Environment and Sustainability, 141 pp., 2013.  
USDA-NCSS: National Cooperative Soil Survey (NCSS) Soil Characterization Database, United States Department of Agriculture, Natural Resources Conservation Service, Lincoln, 2018.  
van Engelen, V. W. P. and Dijkshoorn, J. A.: Global and National Soils and Terrain Digital Databases (SOTER) - Procedures manual (Ver. 2.0), IUSS, ISRIC and FAO, Wageningen, ISRIC Report 2013/04, 198 pp., 2013.  
van Ittersum, M. K., Cassman, K. G., Grassini, P., Wolf, J., Tittonell, P., and Hochman, Z.: Yield gap analysis with local to global relevance – A review, Field Crop. Res., 143, 4–17, 2013.  
Van Looy, K., Bouma, J., Herbst, M., Koestel, J., Minasny, B., Mishra, U., Montzka, C., Nemes, A., Pachepsky, Y., Padarian, J., Schaap, M., Tóth, B., Verhoef, A., Vanderborght, J., van der Ploeg, M., Weihermüller, L., Zacharias, S., Zhang, Y., and Vereecken, H. C. R. G.: Pedotransfer functions in Earth system science: challenges and perspectives, Rev. Geophys., 55, 1199–1256, https://doi.org/10.1002/2017RG000581, 2017.  
van Reeuwijk, L. P.: On the way to improve international soil classification and correlation: the variability of soil analytical data, ISRIC, Wageningen, Annual Report 1983, 7–13, 1983.  
van Reeuwijk, L. P.: Guidelines for quality management in soil and plant laboratories, FAO, Rome, 143 pp., 1998.  
Viscarra Rossel, R. A., Behrens, T., Ben-Dor, E., Brown, D. J., Demattê, J. A. M., Shepherd, K. D., Shi, Z., Stenberg, B., Stevens, A., Adamchuk, V., Aïchi, H., Barthès, B. G., Bartholomeus, H. M., Bayer, A. D., Bernoux, M., Böttcher, K., Brodský, L., Du, C. W., Chappell, A., Fouad, Y., Genot, V., Gomez, C., Grunwald, S., Gubler, A., Guerrero, C., Hedley, C. B., Knadel, M., Morrás, H. J. M., Nocita, M., Ramirez-Lopez, L., Roudier, P., Campos, E. M. R., Sanborn, P., Sellitto, V. M., Sudduth, K. A., Rawlins, B. G., Walter, C., Winowiecki, L. A., Hong, S. Y., and Ji, W.: A global spectral library to


---


characterize the world’s soil, Earth-Sci. Rev., 155, 198–230,  
https://doi.org/10.1016/j.earscirev.2016.01.012, 2016.  
WEPAL: ISE Reference Material – A list with all available ISE reference material samples, WEPAL (Wageningen Evaluating Programmes for Analytical Laboratories), Wageningen, 110 pp., 2019.  
Wilkinson, M. D., Dumontier, M., Aalbersberg, I. J., Appleton, G., Axton, M., Baak, A., Blomberg, N., Boiten, J.-W., da Silva Santos, L. B., Bourne, P. E., Bouwman, J., Brookes, A. J., Clark, T., Crosas, M., Dillo, I., Dumon, O., Edmunds, S., Evelo, C. T., Finkers, R., Gonzalez-Beltran, A., Gray, A. J. G., Groth, P., Goble, C., Grethe, J. S., Heringa, J., ’t Hoen, P. A. C., Hooft, R., Kuhn, T., Kok, R., Kok, J., Lusher, S. J., Martone, M. E., Mons, A., Packer, A. L., Persson, B., Rocca-Serra, P., Roos, M., van Schaik, R., Sansone, S.-A., Schultes, E., Sengstag, T., Slater, T., Strawn, G., Swertz, M. A., Thompson, M., van der Lei, J., van Mulligen, E., Velterop, J., Waagmeester, A., Wittenburg, P., Wolstencroft, K., Zhao, J., and Mons, B.: The FAIR Guiding Principles for scientific data management and stewardship, Scientific Data, 3, 160018, https://doi.org/10.1038/sdata.2016.18, 2016.
