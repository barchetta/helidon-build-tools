/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.build.archetype.maven;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.stream.Collectors;

import io.helidon.build.archetype.engine.ArchetypeDescriptor;
import io.helidon.build.archetype.engine.ArchetypeEngine;
import io.helidon.build.archetype.engine.Maps;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.ModelWriter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * {@code archetype:jar} mojo.
 */
@Mojo(name = "jar", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class JarMojo extends AbstractMojo {

    /**
     * The plugin groupId.
     */
    private static final String GROUP_ID = "io.helidon.build-tools.archetype";

    /**
     * The plugin artifactId.
     */
    private static final String PLUGIN_ARTIFACT_ID = "helidon-archetype-maven-plugin";

    /**
     * The archetype engine artifactId.
     */
    private static final String ENGINE_ARTIFACT_ID = "helidon-archetype-engine";

    /**
     * The resource name for the {@code pom.properties} file included in the plugin JAR file.
     */
    private static final String POM_PROPERTIES_RESOURCE_NAME = "/META-INF/maven/"
            + GROUP_ID + "/"
            + PLUGIN_ARTIFACT_ID + "/pom.properties";

    /**
     * The archetype engine version.
     */
    private static final String ENGINE_VERSION = getEngineVersion();

    /**
     * The name for the post groovy script.
     */
    private static final String POST_SCRIPT_NAME = "archetype-post-generate.groovy";

    /**
     * The file extension of mustache template files.
     */
    private static final String MUSTACHE_EXT = ".mustache";

    /**
     * Plexus build context used to get the scanner for scanning resources.
     */
    @Component
    private BuildContext buildContext;

    /**
     * The Maven project this mojo executes on.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The project build output directory. (e.g. {@code target/})
     */
    @Parameter(defaultValue = "${project.build.directory}",
            readonly = true, required = true)
    private File outputDirectory;

    /**
     * Name of the generated JAR.
     */
    @Parameter(defaultValue = "${project.build.finalName}", alias = "jarName", required = true)
    private String finalName;

    /**
     * The {@link MavenSession}.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The Jar archiver.
     */
    @Component
    private Map<String, Archiver> archivers;

    /**
     * The pom reader.
     */
    @Component
    private ModelReader modelReader;

    /**
     * The pom writer.
     */
    @Component
    private ModelWriter modelWriter;

    /**
     * The archive configuration to use.
     */
    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    /**
     * Timestamp for reproducible output archive entries.
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Properties to use for pre-processing.
     */
    @Parameter
    private Map<String, String> properties = Collections.emptyMap();

    /**
     * Include project properties for pre-processing.
     */
    @Parameter(defaultValue = "true")
    private boolean includeProjectProperties;

    /**
     * Indicate if the generated JAR should be compatible with the {@code maven-archetype-plugin}.
     */
    @Parameter(defaultValue = "true")
    private boolean mavenArchetypeCompatible;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path archetypeDir = outputDirectory.toPath().resolve("archetype");
        Path baseDir = project.getBasedir().toPath();
        Path archetypeDescriptor = archetypeDir.resolve(ArchetypeEngine.DESCRIPTOR_RESOURCE_NAME);
        Path archetypeResourcesList = archetypeDir.resolve(ArchetypeEngine.RESOURCES_LIST);

        Map<String, List<String>> resources = scanResources();
        processDescriptor(resources, baseDir, archetypeDescriptor);
        if (mavenArchetypeCompatible) {
            processMavenCompat(archetypeDir, archetypeDescriptor);
        }
        processArchetypeResources(resources, archetypeDir, baseDir, archetypeResourcesList);

        File jarFile = generateArchetypeJar(archetypeDir);
        project.getArtifact().setFile(jarFile);
    }

    /**
     * Process the archetype descriptor.
     * Find a descriptor template and if present generate the descriptor from it, otherwise find a descriptor copy it.
     *
     * @param resources           scanned project resources
     * @param baseDir             project base directory
     * @param archetypeDescriptor target file
     * @throws MojoFailureException   if the no template and no descriptor is found
     * @throws MojoExecutionException if an IO error occurs
     */
    private void processDescriptor(Map<String, List<String>> resources, Path baseDir, Path archetypeDescriptor)
            throws MojoFailureException, MojoExecutionException {

        try {
            getLog().info("Processing archetype descriptor");

            // create target/archetype/META-INF
            Files.createDirectories(archetypeDescriptor.getParent());

            // find a descriptor template
            Path archetypeDescriptorTemplate = findResource(resources, baseDir,
                    ArchetypeEngine.DESCRIPTOR_RESOURCE_NAME + MUSTACHE_EXT);
            if (archetypeDescriptorTemplate != null) {
                preProcessDescriptor(archetypeDescriptorTemplate, archetypeDescriptor);
            } else {
                // or else copy a descriptor
                Path archetypeDescriptorSource = findResource(resources, baseDir,
                        ArchetypeEngine.DESCRIPTOR_RESOURCE_NAME);
                if (archetypeDescriptorSource == null) {
                    throw new MojoFailureException(ArchetypeEngine.DESCRIPTOR_RESOURCE_NAME + " not found");
                }
                getLog().info("Copying helidon-archetype.xml from " + archetypeDescriptorSource);
                Files.copy(archetypeDescriptorSource, archetypeDescriptor, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    /**
     * Generate the resources required to make the archetype JAR compatible with the {@code maven-archetype-plugin}.
     * @param archetypeDir the exploded archetype directory
     * @param archetypeDescriptor the helidon archetype descriptor
     * @throws MojoExecutionException if an IO error occurs
     */
    private void processMavenCompat(Path archetypeDir, Path archetypeDescriptor) throws MojoExecutionException {
        try {
            getLog().info("Processing maven-archetype-plugin compatibility");

            Path mavenArchetypeDescriptor = archetypeDir.resolve("META-INF/maven/archetype-metadata.xml");

            // create target/archetype/META-INF/maven
            Files.createDirectories(mavenArchetypeDescriptor.getParent());

            ArchetypeDescriptor desc = ArchetypeDescriptor.read(Files.newInputStream(archetypeDescriptor));

            // create target/archetype/META-INF/maven/archetype-metadata.xml
            try (BufferedWriter writer = Files.newBufferedWriter(mavenArchetypeDescriptor)) {
                StringWriter sw = new StringWriter();
                DescriptorConverter.convert(desc, sw);
                writer.append(sw.toString());
            }

            Path mavenArchetypePom = archetypeDir.resolve("archetype-resources/pom.xml");

            // create target/archetype/archetype-resources
            Files.createDirectories(mavenArchetypePom.getParent());

            // create an empty file target/archetype/archetype-resources/pom.xml
            if (!Files.exists(mavenArchetypePom)) {
                Files.createFile(mavenArchetypePom);
            }

            // check if the engine dependency exists
            boolean engineDependencyFound = false;
            for (Artifact artifact : project.getArtifacts()) {
                if (artifact.getGroupId().equals(GROUP_ID)
                    && artifact.getArtifactId().equals(ENGINE_ARTIFACT_ID)
                    && artifact.getVersion().equals(ENGINE_VERSION)) {
                    engineDependencyFound = true;
                    break;
                }
            }

            if (!engineDependencyFound) {
                getLog().info("Updating project pom to include a dependency on helidon-archetype-engine");

                Model projectModel = modelReader.read(project.getFile(), null);
                Dependency dep = new Dependency();
                dep.setGroupId(GROUP_ID);
                dep.setArtifactId(ENGINE_ARTIFACT_ID);
                dep.setVersion(ENGINE_VERSION);
                projectModel.getDependencies().add(dep);
                // write the updated version of the pom
                File newPomFile = new File(outputDirectory, "pom.xml");
                writeModel(projectModel, project.getFile(), newPomFile);
                // attach it
                project.setFile(newPomFile);
            }

            getLog().info("Rendering archetype-post-generate.groovy");

            Path postGroovyScript = archetypeDir.resolve("META-INF/" + POST_SCRIPT_NAME);

            // compile the template
            MustacheFactory mf = new DefaultMustacheFactory();
            Mustache m = mf.compile(new InputStreamReader(
                    getClass().getResourceAsStream("/" + POST_SCRIPT_NAME + MUSTACHE_EXT)),
                    POST_SCRIPT_NAME);

            Map<String, Object> props = Map.of("propNames", desc.properties().stream()
                    .filter(prop -> prop.isExported())
                    .map(prop -> prop.id())
                    .collect(Collectors.toList()),
                    "engineGroupId", GROUP_ID,
                    "engineArtifactId", ENGINE_ARTIFACT_ID,
                    "engineVersion", ENGINE_VERSION);

            // render
            try (Writer writer = Files.newBufferedWriter(postGroovyScript, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                m.execute(writer, props).flush();
            }
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    /**
     * Write a model to a file.
     * This implementation preserves the comment (copyright headers).
     *
     * @param model      the model to write
     * @param pomFile    the source pom file
     * @param newPomFile the target pom file
     * @throws MojoExecutionException if an IO error occurs
     */
    private void writeModel(Model model, File pomFile, File newPomFile) throws MojoExecutionException {
        try (FileWriter fw = new FileWriter(newPomFile)) {
            // write comments from base pom
            String line;
            BufferedReader br = new BufferedReader(new FileReader(pomFile));
            while (true) {
                line = br.readLine();
                if (line == null || line.startsWith("<project")) {
                    break;
                }
                fw.write(line);
                fw.write('\n');
            }

            // write new pom and skip first line (xml header)
            String pom = modelAsString(model);
            int ind = pom.indexOf("<project");
            fw.write(pom.substring(ind));
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    /**
     * Write the project model into as String.
     * @return String
     * @throws MojoExecutionException if an IO error occurs
     */
    private String modelAsString(Model model) throws MojoExecutionException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            modelWriter.write(baos, /* options */ null, model);
            return new String(baos.toByteArray());
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    /**
     * Process the archetype resources.
     * Copy the archetype resources, and generate the resources list file.
     *
     * @param archetypeDir           the exploded archetype directory
     * @param baseDir                the project base directory
     * @param resources              the scanned project resources
     * @param archetypeResourcesList the archetype resource list file to generate
     * @throws MojoExecutionException if an IO error occurs
     */
    private void processArchetypeResources(Map<String, List<String>> resources,
                                           Path archetypeDir,
                                           Path baseDir,
                                           Path archetypeResourcesList)
            throws MojoExecutionException {

        getLog().info("Processing archetype resources");

        // create target/archetype/META-INF/helidon-archetype-resources.txt
        // copy archetype resources to target/archetype/
        try (BufferedWriter writer = Files.newBufferedWriter(archetypeResourcesList)) {
            PrintWriter printer = new PrintWriter(writer);
            for (Entry<String, List<String>> resourcesEntry : resources.entrySet()) {
                getLog().debug("processing resources scanned from: " + resourcesEntry.getKey());
                for (String resource : resourcesEntry.getValue()) {
                    if (resource.startsWith("META-INF/")) {
                        continue;
                    }
                    getLog().debug("adding resource to archetype manifest: " + resource);
                    printer.println(resource);
                    Path resourceTarget = archetypeDir.resolve(resource);
                    getLog().debug("adding resource to archetype directory: " + resource);
                    Files.createDirectories(resourceTarget);
                    Files.copy(baseDir.resolve(resourcesEntry.getKey()).resolve(resource), resourceTarget,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    /**
     * Generate the archetype JAR file.
     *
     * @param archetypeDir the exploded archetype directory
     * @return created file
     * @throws MojoExecutionException if an error occurs
     */
    private File generateArchetypeJar(Path archetypeDir) throws MojoExecutionException {
        File jarFile = new File(outputDirectory, finalName + ".jar");

        MavenArchiver archiver = new MavenArchiver();
        archiver.setCreatedBy("Helidon Archetype Plugin", "io.helidon.build-tools.archetype",
                "helidon-archetype-maven-plugin");
        archiver.setOutputFile(jarFile);
        archiver.setArchiver((JarArchiver) archivers.get("jar"));
        archiver.configureReproducible(outputTimestamp);

        try {
            archiver.getArchiver().addDirectory(archetypeDir.toFile());
            archiver.createArchive(session, project, archive);
        } catch (IOException | DependencyResolutionRequiredException | ArchiverException | ManifestException e) {
            throw new MojoExecutionException("Error assembling archetype jar " + jarFile, e);
        }
        return jarFile;
    }

    /**
     * Find a resource file.
     *
     * @param resources scanned project resources
     * @param baseDir   project base directory
     * @param name      name of the resource to find
     * @return Path or {@code null} if not found
     */
    private Path findResource(Map<String, List<String>> resources, Path baseDir, String name) {
        return resources.entrySet().stream()
                .filter(e -> e.getValue().contains(name))
                .map(e -> baseDir.resolve(e.getKey()).resolve(name))
                .findAny()
                .orElse(null);
    }


    /**
     * Process a mustache template for an archetype descriptor.
     *
     * @param template            mustache template
     * @param archetypeDescriptor the target file
     */
    private void preProcessDescriptor(Path template, Path archetypeDescriptor) {
        try {
            getLog().info("Rendering helidon-archetype.xml from " + template);

            // pre-processing properties
            Map<String, String> props = new HashMap<>();
            props.putAll(properties);
            if (includeProjectProperties) {
                Properties projectProperties = project.getProperties();
                props.putAll(Maps.fromProperties(project.getProperties()));
                props.put("project.groupId", project.getGroupId());
                props.put("project.artifactId", project.getArtifactId());
                props.put("project.version", project.getVersion());
                props.put("project.name", project.getName());
                props.put("project.description", project.getDescription());
            }

            // compile the template
            MustacheFactory mf = new DefaultMustacheFactory();
            Mustache m = mf.compile(new InputStreamReader(Files.newInputStream(template)),
                    ArchetypeEngine.DESCRIPTOR_RESOURCE_NAME + MUSTACHE_EXT);

            // render
            try (Writer writer = Files.newBufferedWriter(archetypeDescriptor, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                m.execute(writer, props).flush();
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Scan for project resources and produce a comma separated list of include resources.
     *
     * @return list of resources
     */
    private Map<String, List<String>> scanResources() {
        getLog().debug("Scanning project resources");
        Map<String, List<String>> allResources = new HashMap<>();
        for (Resource resource : project.getResources()) {
            List<String> resources = new ArrayList<>();
            allResources.put(resource.getDirectory(), resources);
            File resourcesDir = new File(resource.getDirectory());
            Scanner scanner = buildContext.newScanner(resourcesDir);
            String[] includes = null;
            if (resource.getIncludes() != null
                    && !resource.getIncludes().isEmpty()) {
                includes = (String[]) resource.getIncludes()
                        .toArray(new String[resource.getIncludes().size()]);
            }
            scanner.setIncludes(includes);
            String[] excludes = null;
            if (resource.getExcludes() != null
                    && !resource.getExcludes().isEmpty()) {
                excludes = (String[]) resource.getExcludes()
                        .toArray(new String[resource.getExcludes().size()]);
            }
            scanner.setExcludes(excludes);
            scanner.scan();
            for (String included : scanner.getIncludedFiles()) {
                getLog().debug("Found resource: " + included);
                resources.add(included);
            }
        }
        return allResources;
    }

    /**
     * Get the archetype engine version from the maven plugin JAR file.
     *
     * @return version, never {@code null}
     * @throws IllegalStateException if the version is {@code null} or if an IO error occurs
     */
    private static String getEngineVersion() {
        try {
            Properties props = new Properties();
            props.load(JarMojo.class.getResourceAsStream(POM_PROPERTIES_RESOURCE_NAME));
            String version = props.getProperty("version");
            if (version == null) {
                throw new IllegalStateException("Unable to resolve engine version");
            }
            return version;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}