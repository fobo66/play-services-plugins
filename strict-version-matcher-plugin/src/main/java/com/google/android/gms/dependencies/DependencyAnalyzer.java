package com.google.android.gms.dependencies;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * {Dependency} collector and analyzer for build artifacts.
 * <p>
 * Dependencies between artifacts can can be registered via register* methods. Then, the
 * dependencies that apply to a set of resolved artifacts can be retrieved to understand whether
 * dependency resolution is ignoring certain declared dependencies. The dependencies that are
 * registered can also be retrieved by non-version-specific artifact type.
 * <p>
 * This class is used in the plugin to register all known dependencies between version-specific
 * artifacts and then allow post-Gradle-dependency-resolution analysis to happen. An internal tree
 * is kept that allows version paths to the artifacts versions to be displayed
 * <p>
 * Thread-safety is provided via blocking and deep object copies.
 * <p>
 * TODO: Support SemVer qualifiers.
 */
public class DependencyAnalyzer {
  private Logger logger = Logging.getLogger(DependencyAnalyzer.class);

  private ArtifactDependencyManager dependencyManager = new ArtifactDependencyManager();

  /**
   * Register a {Dependency}.
   */
  synchronized void registerDependency(@Nonnull Dependency dependency) {
    dependencyManager.addDependency(dependency);
  }

  /**
   * Returns a set of Dependencies that were registered between the ArtifactVersions supplied.
   *
   * @param versionedArtifacts List of ArtifactVersions to return dependencies for.
   *
   * @return Dependencies found or an empty collection.
   */
  @Nonnull
  synchronized Collection<Dependency> getActiveDependencies(
      Collection<ArtifactVersion> versionedArtifacts) {
    // Summarize the artifacts in play.
    HashSet<Artifact> artifacts = new HashSet<>();
    HashSet<ArtifactVersion> artifactVersions = new HashSet<>();
    for (ArtifactVersion version : versionedArtifacts) {
      if (version.getGroupId().equals("com.google.android.gms")) {
        logger.debug("Getting artifact: " + version + ":" + version.getArtifact());
      }
      artifacts.add(version.getArtifact());
      artifactVersions.add(version);
    }

    // Find all the dependencies that we need to enforce.
    ArrayList<Dependency> dependencies = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      for (Dependency dep : dependencyManager.getDependencies(artifact)) {
        if (artifactVersions.contains(dep.getFromArtifactVersion()) &&
            artifacts.contains(dep.getToArtifact())) {
          dependencies.add(dep);
        }
      }
    }
    return dependencies;
  }

  synchronized Collection<Node> getPaths(Artifact artifact) {
    ArrayList<Node> l = new ArrayList<>();
    Collection<Dependency> deps = dependencyManager.getDependencies(artifact);
    for (Dependency d : deps) {
      // Proceed to report back info.
      getNode(l, new Node(null, d), d.getFromArtifactVersion());
    }
    return l;
  }

  private synchronized void getNode(ArrayList<Node> terminalPathList, Node n,
                                    ArtifactVersion artifactVersion) {
    Collection<Dependency> deps = dependencyManager.getDependencies(artifactVersion.getArtifact());
    if (deps.size() < 1) {
      terminalPathList.add(n);
      return;
    }
    for (Dependency d : deps) {
      if (d.isVersionCompatible(artifactVersion.getVersion())) {
        // Proceed to report back info.
        getNode(terminalPathList, new Node(n, d), d.getFromArtifactVersion());
      }
    }
  }
}