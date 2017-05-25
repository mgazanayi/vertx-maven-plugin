package io.fabric8.vertx.maven.plugin.components.impl;

import io.fabric8.vertx.maven.plugin.components.*;
import io.fabric8.vertx.maven.plugin.mojos.Archive;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.shared.artifact.filter.resolve.ScopeFilter;
import org.apache.maven.shared.artifact.filter.resolve.transform.ArtifactIncludeFilterTransformer;
import org.codehaus.plexus.component.annotations.Component;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

import java.io.*;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
@Component(
    role = PackageService.class,
    hint = "fat-jar")
public class ShrinkWrapFatJarPackageService implements PackageService {
    @Override
    public PackageType type() {
        return PackageType.FAT_JAR;
    }

    @Override
    public File doPackage(PackageConfig config) throws
        PackagingException {

        Log logger = Objects.requireNonNull(config.getMojo().getLog());
        Archive archive = Objects.requireNonNull(config.getArchive());

        Set<Artifact> artifactsToIncludes = new LinkedHashSet<>();
        for (DependencySet ds : archive.getDependencySets()) {
            try {
                ScopeFilter scopeFilter = ServiceUtils.newScopeFilter(ds.getScope());
                ArtifactFilter filter = new ArtifactIncludeFilterTransformer().transform(scopeFilter);
                artifactsToIncludes.addAll(ServiceUtils.filterArtifacts(config.getArtifacts(),
                    ds.getIncludes(), ds.getExcludes(),
                    true, logger, filter));
            } catch (InvalidAssemblerConfigurationException e) {
                throw new PackagingException(e);
            }
        }

        // TODO file

        JavaArchive jar = ShrinkWrap.create(JavaArchive.class);

        // Add dependencies
        for (Artifact artifact : artifactsToIncludes) {
            File file = artifact.getFile();
            if (file.isFile()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Adding Dependency :" + artifact);
                }
                importFromFile(logger, jar, file);
            } else {
                logger.info("Cannot embed artifact " + artifact + " - the file does not exist");
            }
        }

        // Add classes
        if (config.isIncludeClasses()) {
            File classes = new File(config.getProject().getBuild().getOutputDirectory());
            if (classes.isDirectory()) {
                jar.addAsResource(classes, "/");
            }
        }

        // Generate manifest
        try {
            generateManifest(jar, archive.getManifest());
        } catch (IOException | MojoExecutionException e) {
            throw new PackagingException(e);
        }

        // Generate output file
        File jarFile;

        try {
            jarFile = config.getOutput();

            boolean useTmpFile = false;
            File theCreatedFile = jarFile;
            if (jarFile.isFile()) {
                useTmpFile = true;
                theCreatedFile = new File(jarFile.getParentFile(), jarFile.getName() + ".tmp");
            }

            jar.as(ZipExporter.class).exportTo(theCreatedFile);

            if (useTmpFile) {
                jarFile.delete();
                theCreatedFile.renameTo(jarFile);
            }

        } catch (Exception e) {
            throw new PackagingException(e);
        }

        return jarFile;
    }


    private boolean toExclude(ArchivePath path) {
        String name = path.get();
        // Default
        if (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")) {
            return true;
        }
        if (name.equalsIgnoreCase("/META-INF/INDEX.LIST")) {
            return true;
        }
        if (name.equalsIgnoreCase("/META-INF/MANIFEST.MF")) {
            return true;
        }

        // TODO custom exclude ?
        return false;
    }

    /**
     * Import from file and make sure the file is closed.
     *
     * @param log  the logger
     * @param jar  the archive
     * @param file the file, must not be {@code null}
     */
    private void importFromFile(Log log, JavaArchive jar, File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            jar.as(ZipImporter.class).importFrom(fis, path -> {
                if (!toExclude(path)) {
                    return true;
                } else {
                    log.debug("Excluding " + path.get() + " from " + file.getName());
                    return false;
                }
            });
            IOUtils.closeQuietly(fis);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to read the file " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Generate the manifest for the über jar.
     */
    private void generateManifest(JavaArchive jar, Map<String, String> entries) throws IOException,
        MojoExecutionException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (entries != null) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                attributes.put(new Attributes.Name(entry.getKey()), entry.getValue());
            }
        }

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        manifest.write(bout);
        bout.close();
        byte[] bytes = bout.toByteArray();
        //TODO: merge existing manifest with current one
        jar.setManifest(new ByteArrayAsset(bytes));

    }
}
