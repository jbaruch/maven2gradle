/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * This script obtains  the effective pom of the current project, reads its dependencies
 * and generates build.gradle scripts. It also generates settings.gradle for multimodule builds. <br/>
 *
 * It currently supports both single-module and multi-module POMs, inheritance, dependency management, properties - everything.
 *
 * @author Antony Stubbs <antony.stubbs@gmail.com>
 * @author Baruch Sadogursky <jbaruchs@gmail.com>
 */

// debug - print out our current working directory
println "Working path:" + new File(".").getAbsolutePath() + "\n"

String pomContents = getEffectivePomContents()

if (pomContents != null && !pomContents.empty) {
// use the Groovy XmlSlurper library to parse the text string and get a reference to the outer project element.
  def root = new XmlSlurper().parseText(pomContents)
  String build
  def reactorProjects = []
  def multimodule = root.name() == "projects"
  if (multimodule) {
    println "This is multi-module project.\n"
    def allProjects = root.project
    print "Configuring Dependencies... "
    Map<String, String> dependencies = new HashMap<String, String>();
    allProjects.each {
      if (it.packaging == "pom") {
        reactorProjects.add(it)
      }
      dependencies.put(it.artifactId.toString(), getDependencies(it, reactorProjects, allProjects))
    }
    println "Done."

    print "Generating settings.gradle... "
    generateSettings(allProjects);
    println "Done."

    build = "allprojects  {\n" +
            "\tusePlugin('java')\n" +
            "\tusePlugin('maven')\n\n" +
            getArtifactData(allProjects[0]) + "\n" +
            "\tconfigurations.compile.transitive = true\n" +
            "}\n\n" +
            "subprojects {\n" +
            getCompilerSettings(allProjects[0]) + "\n" +
            getRepositoriesForProjects(allProjects) + "\n" +
            dependencies.get(allProjects[0].artifactId.toString()) +
            "}\n" +
            "\n" +
            "dependsOnChildren()"

    reactorProjects.each {
      it.modules.module.each {
        String moduleName = it;
        def submodule = allProjects.find {
          new File(it.build.directory.toString()).parentFile.name == moduleName && !it.packaging.toString().equals("pom")
        }
        if (!submodule.isEmpty()) {
          File submoduleDir = new File(submodule.build.directory.toString()).parentFile

          def submoduleId = submodule.artifactId.toString()
          String moduleDependencies = dependencies.get(submoduleId)
          boolean warPack = submodule.packaging.toString().equals("war")
          def hasDependencies = !(moduleDependencies == null || moduleDependencies.isEmpty())
          if (warPack || hasDependencies) {
            print "Generating build.gradle for module ${submoduleId}... "
            File submoduleBuildFile = new File(submoduleDir, "build.gradle")
            String moduleBuild = ""
            if (warPack) {
              moduleBuild += "usePlugin 'war'\n\n"
            }
            if (hasDependencies) {
              moduleBuild += moduleDependencies
            }
            if (submoduleBuildFile.exists()) {
              print "(backing up existing one)... "
              submoduleBuildFile.renameTo(new File("build.gradle.bak"))
            }
            submoduleBuildFile.text = moduleBuild
            println "Done."
          }
        }
      }
    }
    //TODO children builds
    //TODO deployment
  } else {//simple
    println "This is single module project."
    build = "usePlugin 'java'\n" +
            "usePlugin 'maven'\n\n" +
            getArtifactData(root) + "\n" +
            "configurations.compile.transitive = true\n\n" +
            getCompilerSettings(root) + "\n"

    print "Configuring Maven repositories... "
    String repos = "repositories {\n"
    Set<String> repoSet = new HashSet<String>();
    getRepositoriesForModule(root, repoSet)
    repoSet.each {
      repos = "${repos} ${it}\n"
    }
    build += "${repos}}\n"
    println "Done."
    print "Configuring Dependencies... "
    String dependencies = getDependencies(root, reactorProjects, null)
    build += dependencies
    println "Done."
  }
  print "Generating main build.gradle... "
  def buildFile = new File("build.gradle")
  if (buildFile.exists()) {
    print "(backing up existing one)... "
    buildFile.renameTo(new File("build.gradle.bak"))
  }
  buildFile.text = build
  println "Done."
}

private String getArtifactData(project) {
  return "\tgroup = '${project.groupId}'\n" +
          "\tversion = '${project.version}'\n";
}

private String getRepositoriesForProjects(projects) {
  print "Configuring Repositories... "
  String repos = "\trepositories {\n"
  Set<String> repoSet = new HashSet<String>();
  projects.each {
    getRepositoriesForModule(it, repoSet)
  }
  repoSet.each {
    repos = "${repos} ${it}\n"
  }
  repos = "${repos}\t}\n"
  println "Done."
  return repos
}

private void getRepositoriesForModule(module, repoSet) {
  module.repositories.repository.each {
    repoSet.add("\t\tmavenRepo urls: [\"${it.url}\"]")
  }
  module.pluginRepositories.pluginRepository.each {
    repoSet.add("\t\tmavenRepo urls: [\"${it.url}\"]")
  }
}

private String getDependencies(project, reactorProjects, allProjects) {
// use GPath to navigate the object hierarchy and retrieve the collection of dependency nodes.
  def dependencies = project.dependencies.dependency

  def compileTimeScope = []
  def runTimeScope = []
  def testScope = []
  def providedScope = []
  def systemScope = []

  //cleanup duplicates from parent

// using Groovy Looping and mapping a Groovy Closure to each element, we collect together all
// the dependency nodes into corresponding collections depending on their scope value.
  dependencies.each() {
    if (!duplicateDependency(it, project, allProjects)) {
      def scope = (elementHasText(it.scope)) ? it.scope : "compile"
      switch (scope) {
        case "compile":
          compileTimeScope.add(it)
          break
        case "test":
          testScope.add(it)
          break
        case "provided":
          providedScope.add(it)
          break
        case "runtime":
          runTimeScope.add(it)
          break
        case "system":
          systemScope.add(it)
          break
      }
    }
  }

/**
 * print function then checks the exclusions node to see if it exists, if
 * so it branches off, otherwise we call our simple print function
 */
  def createGradleDep = {String scope, StringBuilder sb, iterator ->
    if (!reactorProjects.isEmpty() && iterator.groupId == project.groupId && projectContainsModule(reactorProjects, iterator.artifactId)) {
      createProjectDependency(iterator, sb, scope)
    } else {
      def exclusions = iterator.exclusions.exclusion
      if (exclusions.size() > 0) {
        createComplexDependency(iterator, sb, scope)
      } else {
        createBasicDependency(iterator, sb, scope)
      }
    }
  }


  StringBuilder build = new StringBuilder()
  if (!compileTimeScope.isEmpty() || !runTimeScope.isEmpty() || !testScope.isEmpty() || !providedScope.isEmpty() || !systemScope.isEmpty()) {
    build.append("\tdependencies {").append("\n")
// for each collection, one at a time, we take each element and call our print function
    if (!compileTimeScope.isEmpty()) compileTimeScope.each() { createGradleDep("compile", build, it) }
    if (!runTimeScope.isEmpty()) runTimeScope.each() { createGradleDep("runtime", build, it) }
    if (!testScope.isEmpty()) testScope.each() { createGradleDep("testCompile", build, it) }
    if (!providedScope.isEmpty()) providedScope.each() { createGradleDep("provided", build, it) }
    if (!systemScope.isEmpty()) systemScope.each() { createGradleDep("system", build, it) }
    build.append("\t}\n")
  }
  return build.toString();
}

def projectContainsModule(def reactorProjects, def artifactId) {
  def contains = false;
  reactorProjects.each {
    it.modules.module.each() {
      if (it == artifactId) contains = true;
    }
  }
  return contains;
}

/**
 * complex print statement does one extra task which is
 * iterate over each
 * �exclusion� node and print out the artifact id.
 */
private def createComplexDependency(it, build, scope) {
  build.append("\t${scope}(\"${contructSignature(it)}\") {\n")
  it.exclusions.exclusion.each() {
    build.append("\t\texclude(module: '${it.artifactId}')\n")
  }
  build.append("\t\t}\n")
}

/**
 * Print out the basic form og gradle dependency
 */
private def createBasicDependency(it, build, String scope) {
  def classifier = contructSignature(it)
  build.append("\t${scope} \"${classifier}\"\n")
}
/**
 * Print out the basic form og gradle dependency
 */
private def createProjectDependency(it, build, String scope) {
  build.append("\t${scope} project(':${it.artifactId}')\n")
}

/**
 * Construct and return the signature of a dependency, including it's version and
 * classifier if it exists
 */
private def contructSignature(it) {
  def gradelDep = "${it.groupId.text()}:${it.artifactId.text()}:${it?.version?.text()}"
  def classifier = elementHasText(it.classifier) ? gradelDep + ":" + it.classifier.text().trim() : gradelDep
  return classifier
}

/**
 * Check to see if the selected node has content
 */
private boolean elementHasText(it) {
  return it.text().length() != 0
}


private String getEffectivePomContents() {
  String pomContents = null;
//let's try to get effective pom first
//TODO work on output stream, without writing to file
  def effectivePomFileName = "effective.pom"
  print "Wait, obtaining effective pom... "

  def ant = new AntBuilder()   // create an antbuilder
  ant.exec(outputproperty: "cmdOut",
          errorproperty: "cmdErr",
          resultproperty: "cmdExit",
          failonerror: "true",
          executable: ((String) System.properties['os.name']).toLowerCase().contains("win") ? "mvn.bat" : "mvn") {
    arg(line: """-Doutput=${effectivePomFileName} help:effective-pom""")
  }

//print the output if verbose flag is on
  println((args.length > 0 && args[0] == "-verbose") ? "\n ${ant.project.properties.cmdOut}" : "Done.")

  if ("0".equals(ant.project.properties.cmdExit)) {
// read in the effective pom file
    File pomFile = new File(effectivePomFileName);
// get it's text into a string
    pomContents = pomFile.text
    pomFile.deleteOnExit()
  } else {
    println "Error obtaining effective pom: ${ant.project.properties.cmdErr}"
  }
  return pomContents
}

private def generateSettings(Object projects) {
  Map<String, String> settingsTree = new HashMap<String, String>();
  projects.each {
    if (it.packaging == "pom") {
      it.modules.module.each {
        //let's figure out if this is a top level module
        if (new File(it.toString()).isDirectory()) { //top level
          settingsTree.put(it.toString(), "'${it}'")
        } else {
          //this is module of subproject, let's find out which
          String moduleName = it;
          def submodule = projects.find {
            new File(it.build.directory.toString()).parentFile.name == moduleName
          }
          File submoduleDir = new File(submodule.build.directory.toString()).parentFile
          String parentModuleName = submoduleDir.parentFile.name
          def existingPart = settingsTree.get(parentModuleName)
          settingsTree.put(parentModuleName, "${existingPart}, '${parentModuleName}:${submoduleDir.name}'")
        }
      }
    }
  }
  def values = settingsTree.values().toString()
  File settingsFile = new File("settings.gradle")
  if (settingsFile.exists()) {
    print "(backing up existing one)... "
    settingsFile.renameTo(new File("settings.gradle.bak"))
  }
  settingsFile.text = "include " + values.substring(1, values.length() - 1)
}

private String getCompilerSettings(project) {
  def plugin = project.build.plugins.plugin.find {
    it.artifactId.toString().equals("maven-compiler-plugin")
  }
  return "\tsourceCompatibility = ${plugin.configuration.source}\n\ttargetCompatibility = ${plugin.configuration.target}\n"
}

private boolean duplicateDependency(dependency, project, allProjects) {
  def parentTag = project.parent
  if (allProjects == null || parentTag.isEmpty()) {//simple project or no parent
    return false;
  } else {
    def parent = allProjects.find {
      it.groupId.equals(parentTag.groupId) && it.artifactId.equals(parentTag.artifactId)
    }
    def duplicate = parent.dependencies.dependency.any {
      it.groupId.equals(dependency.groupId) && it.artifactId.equals(dependency.artifactId)
    }
    if (duplicate) {
      return true;
    } else {
      duplicateDependency(dependency, parent, allProjects)
    }
  }
}