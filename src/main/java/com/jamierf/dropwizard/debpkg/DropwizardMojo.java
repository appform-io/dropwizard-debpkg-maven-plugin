package com.jamierf.dropwizard.debpkg;

import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.jamierf.dropwizard.debpkg.config.*;
import com.jamierf.dropwizard.debpkg.filter.DependencyFilter;
import com.jamierf.dropwizard.debpkg.packaging.PackageBuilder;
import com.jamierf.dropwizard.debpkg.packaging.ResourceExtractor;
import com.jamierf.dropwizard.debpkg.resource.EmbeddedResource;
import com.jamierf.dropwizard.debpkg.resource.FileResource;
import com.jamierf.dropwizard.debpkg.resource.Resource;
import com.jamierf.dropwizard.debpkg.transforms.ResourceProducer;
import com.jamierf.dropwizard.debpkg.util.LogConsole;
import com.jamierf.dropwizard.debpkg.validation.ApplicationValidator;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.tools.tar.TarEntry;
import org.vafer.jdeb.Console;
import org.vafer.jdeb.PackagingException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

@Mojo(name = "dwpackage", defaultPhase = LifecyclePhase.PACKAGE)
public class DropwizardMojo extends AbstractMojo {

    private static final String INPUT_ARTIFACT_TYPE = "jar";
    private static final String OUTPUT_ARTIFACT_TYPE = "deb";

    private static final String DROPWIZARD_GROUP_ID = "io.dropwizard";
    private static final String DROPWIZARD_ARTIFACT_ID = "dropwizard-core";

    private static final String WORKING_DIRECTORY_NAME = "dropwizard-deb-package";

    @SuppressWarnings("OctalInteger")
    private static final int UNIX_MODE_USER_ONLY = 0100600;

    @Component
    protected MavenProjectHelper helper = null;

    @Component
    protected MavenProject project = null;

    @Parameter
    protected DebConfiguration deb = new DebConfiguration();

    @Parameter
    protected JvmConfiguration jvm = new JvmConfiguration();

    @Parameter
    protected UnixConfiguration unix = new UnixConfiguration();

    @Parameter
    protected PathConfiguration path = new PathConfiguration();

    @Parameter
    protected Map<String, String> dropwizard = Collections.emptyMap();

    @Parameter
    protected File configTemplate = null;

    @Parameter
    protected File artifactFile;

    @Parameter
    protected File outputFile;

    @Parameter
    protected PgpConfiguration pgp = null;

    @Parameter
    @SuppressWarnings("FieldCanBeLocal")
    protected boolean validate = true;

    @Parameter
    protected List<ResourceConfiguration> files = Collections.emptyList();

    private Console log = new LogConsole(getLog());

    @Override
    public void execute() throws MojoExecutionException {
        init();

        final Collection<Resource> resources = buildResourceList();
        final Map<String, Object> parameters = buildParameterMap();

        final File resourcesDir = extractResources(resources, parameters);

        if (validate) {
            validateApplicationConfiguration(resourcesDir);
        }

        final File debFile = createPackage(resources, resourcesDir);
        log.info(String.format("Attaching created %s package %s", OUTPUT_ARTIFACT_TYPE, debFile));
        helper.attachArtifact(project, OUTPUT_ARTIFACT_TYPE, debFile);
    }

    protected void init() throws MojoExecutionException {
        log = new LogConsole(getLog()); // Re set up logging in case the log implementation was changed during execution

        checkNotNull(configTemplate);

        deb.setProject(project);
        path.setProject(project);

        if (artifactFile == null) {
            final Artifact artifact = project.getArtifact();
            if (!INPUT_ARTIFACT_TYPE.equals(artifact.getType())) {
                throw new MojoExecutionException(String.format("Artifact type %s not recognised, required %s",
                        artifact.getType(), INPUT_ARTIFACT_TYPE));
            }

            artifactFile = artifact.getFile();
        }

        if (outputFile == null) {
            final String outputFilename = String.format("%s-%s.%s", project.getArtifactId(), project.getVersion(), OUTPUT_ARTIFACT_TYPE);
            outputFile = new File(project.getBuild().getDirectory(), outputFilename);
        }
    }

    @SuppressWarnings("OctalInteger")
    protected Collection<Resource> buildResourceList() {
        return ImmutableList.<Resource>builder()
                .add(new FileResource(configTemplate, true, path.getConfigFile(), unix.getUser(), unix.getUser(), UNIX_MODE_USER_ONLY))
                .add(new EmbeddedResource("/files/jvm.conf", true, path.getJvmConfigFile(), "root", "root", TarEntry.DEFAULT_FILE_MODE))
                .add(new EmbeddedResource("/files/upstart.conf", true, path.getUpstartFile(), "root", "root", TarEntry.DEFAULT_FILE_MODE))
                .add(new EmbeddedResource( "/files/systemv.sh", true, path.getSystemVFile(), "root", "root", TarEntry.DEFAULT_FILE_MODE | 0100111))
                .add(new EmbeddedResource( "/files/systemd.service", true, path.getSystemDFile(), "root", "root", TarEntry.DEFAULT_FILE_MODE))
                .add(new EmbeddedResource("/files/start.sh", true, path.getStartScript(), "root", "root", TarEntry.DEFAULT_FILE_MODE | 0100111))
                .add(new FileResource(artifactFile, false, path.getJarFile(), unix.getUser(), unix.getUser(), TarEntry.DEFAULT_FILE_MODE))
                .addAll(Collections2.transform(files, new ResourceProducer(unix.getUser())))
                .build();
    }

    protected Map<String, Object> buildParameterMap() {
        return ImmutableMap.<String, Object>builder()
                .put("project", project)
                .put("deb", deb)
                .put("jvm", jvm)
                .put("unix", unix)
                .put("dw", dropwizard)
                .put("dropwizard", dropwizard)
                .put("path", path)
                .build();
    }

    protected File extractResources(final Collection<Resource> resources, final Map<String, Object> parameters) throws MojoExecutionException {
        try {
            final File outputDir = new File(project.getBuild().getDirectory(), WORKING_DIRECTORY_NAME);
            new ResourceExtractor(parameters, log).extractResources(resources, outputDir);
            return outputDir;
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to extract resources", e);
        }
    }

    protected void validateApplicationConfiguration(final File resourcesDir) throws MojoExecutionException {
        try {
            final Optional<Dependency> dropwizardDependency = Iterables.tryFind(project.getModel().getDependencies(),
                    new DependencyFilter(DROPWIZARD_GROUP_ID, DROPWIZARD_ARTIFACT_ID));
            if (!dropwizardDependency.isPresent()) {
                log.warn("Failed to find Dropwizard dependency in project. Skipping configuration validation.");
                return;
            }

            final ComparableVersion version = new ComparableVersion(dropwizardDependency.get().getVersion());
            log.info(String.format("Detected Dropwizard %s, attempting to validate configuration.", version));

            final File configFile = new File(resourcesDir, "/files" + path.getConfigFile());
            final ApplicationValidator validator = new ApplicationValidator(artifactFile, log);
            validator.validateConfiguration(configFile);
        }
        catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
            throw new MojoExecutionException("Failed to validate configuration", e);
        }
    }

    protected File createPackage(final Collection<Resource> resources, final File inputDir) throws MojoExecutionException {
        try {
            final URI homepage = project.getUrl() == null ? null : URI.create(project.getUrl());
            new PackageBuilder(project.getArtifactId(), project.getDescription(), homepage, log, Optional.fromNullable(pgp))
                    .createPackage(resources, inputDir, outputFile);
            return outputFile;
        }
        catch (PackagingException e) {
            throw new MojoExecutionException("Failed to create Debian package", e);
        }
    }
}
