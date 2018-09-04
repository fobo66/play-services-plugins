/**
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.oss.licenses.plugin

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Task to find available licenses from the artifacts stored in the json
 * file generated by DependencyTask, and then generate the third_party_licenses
 * and third_party_license_metadata file.
 */
class LicensesTask extends DefaultTask {
    private static final int LINE_SEPARATOR_LEN = System
            .getProperty("line.separator").length()
    private static final int GRANULAR_BASE_VERSION = 14
    private static final String GOOGLE_PLAY_SERVICES_GROUP =
        "com.google.android.gms"
    private static final String LICENSE_ARTIFACT_SURFIX = "-license"
    private static final String FIREBASE_GROUP = "com.google.firebase"
    private static final String UTF_8 = "UTF-8"
    private static final String FAIL_READING_LICENSES_ERROR =
        "Failed to read license text."
    private static final Pattern FILE_EXTENSION = ~/\.[^\.]+$/

    protected int start = 0
    protected Set<String> googleServiceLicenses = []
    protected Map<String, String> licensesMap = [:]

    @InputFile
    public File dependenciesJson

    @OutputDirectory
    public File outputDir

    @OutputFile
    public File licenses

    @OutputFile
    public File licensesMetadata

    @TaskAction
    void action() {
        initOutputDir()
        initLicenseFile()
        initLicensesMetadata()

        def allDependencies = new JsonSlurper().parse(dependenciesJson)
        for (entry in allDependencies) {
            String group = entry.group
            String name = entry.name
            String fileLocation = entry.fileLocation
            String version = entry.version
            File artifactLocation = new File(fileLocation)

            if (isGoogleServices(group, name)) {
                // Add license info for google-play-services itself
                if (!name.endsWith(LICENSE_ARTIFACT_SURFIX)) {
                    addLicensesFromPom(artifactLocation, name, group)
                }
                // Add transitive licenses info for google-play-services. For
                // post-granular versions, this is located in the artifact
                // itself, whereas for pre-granular versions, this information
                // is located at the complementary license artifact as a runtime
                // dependency.
                if (isGranularVersion(version)) {
                    addGooglePlayServiceLicenses(artifactLocation)
                } else if (name.endsWith(LICENSE_ARTIFACT_SURFIX)) {
                    addGooglePlayServiceLicenses(artifactLocation)
                }
            } else {
                addLicensesFromPom(artifactLocation, name, group)
            }
        }

        writeMetadata()
    }

    protected void initOutputDir() {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
    }

    protected void initLicenseFile() {
        if (licenses == null) {
            println("not defined licenses")
        }
        licenses.newWriter().withWriter {w ->
            w << ''
        }
    }

    protected void initLicensesMetadata() {
        licensesMetadata.newWriter().withWriter {w ->
            w << ''
        }
    }

    protected boolean isGoogleServices(String group, String name) {
        return (GOOGLE_PLAY_SERVICES_GROUP.equalsIgnoreCase(group)
                || FIREBASE_GROUP.equalsIgnoreCase(group))
    }

    protected boolean isGranularVersion (String version) {
        String[] versions = version.split("\\.")
        return (versions.length > 0
                && Integer.valueOf(versions[0]) >= GRANULAR_BASE_VERSION)
    }

    protected void addGooglePlayServiceLicenses(File artifactFile) {
        ZipFile licensesZip = new ZipFile(artifactFile)
        JsonSlurper jsonSlurper = new JsonSlurper()

        ZipEntry jsonFile = licensesZip.getEntry("third_party_licenses.json")
        ZipEntry txtFile = licensesZip.getEntry("third_party_licenses.txt")

        if (!jsonFile || !txtFile) {
            return
        }

        Object licensesObj = jsonSlurper.parse(licensesZip.getInputStream(
            jsonFile))
        if (licensesObj == null) {
            return
        }

        for (entry in licensesObj) {
            String key = entry.key
            int startValue = entry.value.start
            int lengthValue = entry.value.length

            if (!googleServiceLicenses.contains(key)) {
                googleServiceLicenses.add(key)
                String content = getTextFromInputStream(
                    licensesZip.getInputStream(txtFile),
                    startValue,
                    lengthValue)
                appendLicense(key, lengthValue, content)
            }
        }
    }

    protected String getTextFromInputStream(
        InputStream stream,
        long offset,
        int length) {
        try {
            byte[] buffer = new byte[1024]
            ByteArrayOutputStream textArray = new ByteArrayOutputStream()

            stream.skip(offset)
            int bytesRemaining = length > 0 ? length : Integer.MAX_VALUE
            int bytes = 0

            while (bytesRemaining > 0
                && (bytes =
                stream.read(
                    buffer,
                    0,
                    Math.min(bytesRemaining, buffer.length)))
                != -1) {
                textArray.write(buffer, 0, bytes)
                bytesRemaining -= bytes
            }
            stream.close()

            return textArray.toString(UTF_8)
        } catch (Exception e) {
            throw new RuntimeException(FAIL_READING_LICENSES_ERROR, e)
        }
    }

    protected void addLicensesFromPom(File artifactFile, String artifactName,
        String group) {
        String pomFileName = artifactFile.getName().replaceFirst(FILE_EXTENSION,
            ".pom")

        // Search for pom file. When the artifact is cached in gradle cache, the
        // pom file will be stored in a hashed directory.
        FileTree tree = project.fileTree(
            dir: artifactFile.parentFile.parentFile,
            include: ["**/${pomFileName}", pomFileName])
        for (File pomFile : tree) {
            def rootNode = new XmlSlurper().parse(pomFile)
            if (rootNode.licenses.size() == 0) continue

            String licenseKey = "${group}:${artifactName}"
            if (rootNode.licenses.size() > 1) {
                rootNode.licenses.license.each { node ->
                    String nodeName = node.name
                    String nodeUrl = node.url
                    appendLicense("${licenseKey} ${nodeName}", nodeUrl.length(),
                        nodeUrl)
                }
            } else {
                String nodeUrl = rootNode.licenses.license.url
                appendLicense(licenseKey, nodeUrl.length(), nodeUrl)
            }
        }
    }

    protected void appendLicense(String key, int length, String content) {
        if (licensesMap.containsKey(key)) {
            return
        }

        licensesMap.put(key, "${start}:${length}")
        licenses.append(content)
        licenses.append(System.getProperty("line.separator"))
        start += length + LINE_SEPARATOR_LEN
    }

    protected void writeMetadata() {
        for (entry in licensesMap) {
            licensesMetadata.append("$entry.value $entry.key")
            licensesMetadata.append(System.getProperty("line.separator"))
        }
    }
}