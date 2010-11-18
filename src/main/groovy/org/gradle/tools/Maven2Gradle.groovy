package org.gradle.tools

import de.uka.ilkd.pp.Layouter
import de.uka.ilkd.pp.StringBackend

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
 * @author Baruch Sadogursky <jbaruch@sadogursky.com>
 * */
class Maven2Gradle {

  String sourceCompatibility
  String targetCompatibility
  def dependentWars = []
  def qualifiedNames
  def workingDir

  public static void main(String[] args) {
    new Maven2Gradle().convert(args)
  }

  def convert(String[] args) {
    workingDir = new File('.').canonicalFile
    println "Working path:" + workingDir.absolutePath + "\n"

    String pomContents = geEffectiveContents('pom', args)

    if (pomContents != null && !pomContents.empty) {
      // use the Groovy XmlSlurper library to parse the text string and get a reference to the outer project element.
      def root = new XmlSlurper().parseText(pomContents)
      String build
      def reactorProjects = []
      def multimodule = root.name() == "projects"
      def pomReplacementPart = """task movePoms(dependsOn: install) << {
${multimodule ? "  subprojects.each {project ->\n" : ""}    def pomsDir = new File(project.buildDir, "poms")
    def defaultPomName = "pom-default.xml"
    def defaultPom = new File(pomsDir, defaultPomName)
    if (defaultPom.exists()) {
      File pomFile = new File(project.projectDir, "pom.xml")
      if (pomFile.exists()) {
        pomFile.renameTo(new File(project.projectDir, "pom.xml.bak"))
      }
      project.copy {
        from(pomsDir) {
          include defaultPomName
          rename defaultPomName, 'pom.xml'
        }
        into(project.projectDir)
      }
    }
${multimodule ? "  }\n" : ""}}
movePoms {
  group = 'Maven'
  description = 'Move pom.xml generated from Gradle script to project root dir (for IDE integraton).'
}
install.group = 'Maven'
"""
      if (multimodule) {
        println "This is multi-module project.\n"
        def allProjects = root.project
        print "Generating settings.gradle... "
        qualifiedNames = generateSettings(workingDir.getName(), allProjects[0].artifactId, allProjects);
        println "Done."
        print "Configuring Dependencies... "
        def dependencies = [:];
        allProjects.each { project ->
          if (project.packaging == "pom") {
            reactorProjects.add(project)
          }
          dependencies[project.artifactId.text()] = getDependencies(project, reactorProjects, allProjects)
        }
        println "Done."


        build = """allprojects  {
  apply plugin: 'java'
  apply plugin: 'maven'

${getArtifactData(allProjects[0])}
}

subprojects {
${getCompilerSettings(allProjects[0])}
${getPackageSources(allProjects[0])}
${getRepositoriesForProjects(allProjects)}
${dependencies.get(allProjects[0].artifactId.text())}}

dependsOnChildren()
"""
        reactorProjects.each { project ->
          project.modules.module.each {
            String moduleName = it;
            def submodule = allProjects.find {
              projectDir(it).name == moduleName && !it.packaging.text().equals("pom")
            }
            if (!submodule.isEmpty()) {
              def submoduleId = submodule.artifactId.text()
              String moduleDependencies = dependencies.get(submoduleId)
              boolean warPack = submodule.packaging.text().equals("war")
              def hasDependencies = !(moduleDependencies == null || moduleDependencies.isEmpty())
              if (warPack || hasDependencies) {
                print "Generating build.gradle for module ${submoduleId}... "
                File submoduleBuildFile = new File(projectDir(submodule), "build.gradle")
                String moduleBuild = """
  description = '${project.name}'
  
"""
                if (warPack) {
                  moduleBuild += """apply plugin: 'war'
"""
                  if (dependentWars.any {mavenDependency ->
                    mavenDependency.groupId.text() == submodule.groupId.text() &&
                            mavenDependency.artifactId.text() == submoduleId
                  }) {
                    moduleBuild += """jar.enabled = true

"""
                  }
                }
                if (hasDependencies) {
                  moduleBuild += moduleDependencies
                }
                if (submoduleBuildFile.exists()) {
                  print "(backing up existing one)... "
                  submoduleBuildFile.renameTo(new File("build.gradle.bak"))
                }
                submoduleBuildFile.text = prettyPrint(moduleBuild)
                println "Done."
              }
            }
          }
        }
        //TODO deployment
      } else {//simple
        println "This is single module project."
        build = """apply plugin: 'java'
apply plugin: 'maven'

${getArtifactData(root)}

description = \"""${root.name}\"""

${getCompilerSettings(root)}

"""

        print "Configuring Maven repositories... "
        Set<String> repoSet = new LinkedHashSet<String>();
        getRepositoriesForModule(root, repoSet)
        String repos = "repositories {\n"
        repoSet.each {
          repos = "${repos} ${it}\n"
        }
        build += "${repos}}\n"
        println "Done."
        print "Configuring Dependencies... "
        String dependencies = getDependencies(root, reactorProjects, null)
        build += dependencies
        println "Done."

        print "Generating settings.gradle if needed... "
        generateSettings(workingDir.getName(), root.artifactId, null);
        println "Done."

      }
      build += """
      ${pomReplacementPart}
      """
      print "Generating main build.gradle... "
      def buildFile = new File("build.gradle")
      if (buildFile.exists()) {
        print "(backing up existing one)... "
        buildFile.renameTo(new File("build.gradle.bak"))
      }
      buildFile.text = prettyPrint(build)
      println "Done."
    }
  }

  def prettyPrint = {raw ->
    StringBackend stringBackend = new StringBackend(80)
    Layouter layouter = new Layouter(stringBackend, 4)
    layouter.print(raw)
    stringBackend.string
  }


  private String getArtifactData(project) {
    return """  group = '$project.groupId'
  version = '$project.version'
  """;
  }

  private String getRepositoriesForProjects(projects) {
    print 'Configuring Repositories... '
    def settings = new XmlSlurper().parseText(geEffectiveContents('settings'))
    //we have local maven repo full with good stuff. Let's reuse it!
    String userHome = System.properties['user.home']
    userHome = userHome.replaceAll('\\\\', '/')
    def localRepoUri = new File(settings.localRepository.text()).toURI().toString()
    if (localRepoUri.contains(userHome)) {
      localRepoUri = localRepoUri.replace(userHome, '${System.properties[\'user.home\']}')
    }
    //in URI format there is one slash after file, while  Gradle needs two
    localRepoUri = localRepoUri.replace('file:/', 'file://')
    String repos = """  repositories {
    mavenRepo urls: ["${localRepoUri}"]
"""
    def repoSet = new LinkedHashSet<String>();
    projects.each {
      getRepositoriesForModule(it, repoSet)
    }
    repoSet.each {
      repos = "${repos} ${it}\n"
    }
    repos = "${repos}}\n"
    println "Done."
    return repos
  }

  private void getRepositoriesForModule(module, repoSet) {
    module.repositories.repository.each {
      repoSet.add("""   mavenRepo urls: [\"${it.url}\"]""")
    }
    //No need to include plugin repos - who cares about maven plugins?
  }

  private String getDependencies(project, reactorProjects, allProjects) {
// use GPath to navigate the object hierarchy and retrieve the collection of dependency nodes.
    def dependencies = project.dependencies.dependency
    def war = project.packaging == "war"

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
    def createGradleDep = {String scope, StringBuilder sb, mavenDependency ->
      def projectFound = allProjects.any { prj ->
        return prj.artifactId == mavenDependency.artifactId
      }
      if (!reactorProjects.isEmpty() && mavenDependency.groupId == project.groupId && projectFound) {
        createProjectDependency(mavenDependency, sb, scope)
      } else {
        def dependencyProperties = null;
        if (!war && scope == 'provided') {
          scope = 'compile'
          dependencyProperties = [provided: true]
        }
        def exclusions = mavenDependency.exclusions.exclusion
        if (exclusions.size() > 0 || dependencyProperties) {
          createComplexDependency(mavenDependency, sb, scope, dependencyProperties)
        } else {
          createBasicDependency(mavenDependency, sb, scope)
        }
      }
    }


    StringBuilder build = new StringBuilder()
    if (!compileTimeScope.isEmpty() || !runTimeScope.isEmpty() || !testScope.isEmpty() || !providedScope.isEmpty() || !systemScope.isEmpty()) {
      build.append("dependencies {").append("\n")
// for each collection, one at a time, we take each element and call our print function
      if (!compileTimeScope.isEmpty()) compileTimeScope.each() { createGradleDep("compile", build, it) }
      if (!runTimeScope.isEmpty()) runTimeScope.each() { createGradleDep("runtime", build, it) }
      if (!testScope.isEmpty()) testScope.each() { createGradleDep("testCompile", build, it) }
      if (!providedScope.isEmpty()) providedScope.each() { createGradleDep("provided", build, it) }
      if (!systemScope.isEmpty()) systemScope.each() { createGradleDep("system", build, it) }
      build.append("}\n")
    }
    return build.toString();
  }

  private String getCompilerSettings(project) {
    def plugin = plugin(project, 'maven-compiler-plugin')
    sourceCompatibility = plugin.configuration.source.text().trim() ? this.plugin.configuration.source : "1.5";
    targetCompatibility = plugin.configuration.target.text().trim() ? this.plugin.configuration.target : "1.5";
    return "sourceCompatibility = ${sourceCompatibility}\ntargetCompatibility = ${targetCompatibility}\n"
  }

  def plugin = {project, artifactId ->
    project.build.plugins.plugin.find {
      it.artifactId.text().equals(artifactId)
    }
  }

  def goalExists = {plugin, goalName ->
    plugin?.executions?.any {->
      execution
      execution?.goals?.any {->
        goal
        goal?.text()?.startsWith(goalName)
      }
    }
  }

  def packSources = {sourceSets ->
    def sourceSetStr = ''
    if (!sourceSets.empty) {
      sourceSetStr = """task packageSources(type: Jar) {
    classifier = 'sources'
    """
      sourceSets.each { sourceSet ->
        sourceSetStr += """from sourceSets.${sourceSet}.allSource
"""
      }
      sourceSetStr += """
    }"""
    }
    return sourceSetStr
  }

  private String getPackageSources(project) {
    def sourcePlugin = plugin(project, 'maven-source-plugn')
    def sourceSets = []
    if (sourcePlugin) {
      if (goalExists(sourcePlugin, 'jar')) {
        sourceSets += 'main'
      } else if (goalExists(sourcePlugin, 'test-jar')) {
        sourceSets += 'test'
      }
    }
    return packSources(sourceSets)
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

  def artifactId = {File dir ->
    new XmlSlurper().parse(new File(dir, 'pom.xml')).artifactId.text()
  }

  def projectDir = {project ->
    new File(project.build.directory.text()).parentFile
  }

  private def generateSettings(def dirName, def mvnProjectName, def projects) {
    def qualifiedNames = [:]
    def projectName = null;
    if (dirName != mvnProjectName) {
      projectName = """rootProject.name = '${mvnProjectName}'
"""
    }
    def modules = new StringBuilder();
    def artifactIdToDir = [:]
    if (projects) {
      projects.each { project ->
        if (project.packaging == "pom") {
          def reactorDir = projectDir(project)
          project.modules.module.each { module ->
            String moduleName = module;
            //let's figure out if this is a top level module
            final def possibleModuleDir = new File(reactorDir, module.text())
            if (possibleModuleDir.directory && possibleModuleDir.absoluteFile.parentFile.absolutePath == workingDir.absolutePath) { //top level
              def artifactId = artifactId(possibleModuleDir)
              if (artifactId != moduleName) {
                artifactIdToDir[artifactId] = possibleModuleDir.name
              }
              modules.append("'$artifactId', ")
            } else {
              //this is module of subproject, let's find out which
              def submodule = projects.find {
                def prjDir = projectDir(it)
                prjDir.name == moduleName && prjDir.parentFile == reactorDir
              }
              def submoduleDir = projectDir(submodule)
              def parentArtifactId = artifactId(submoduleDir.parentFile)
              def artifactId = artifactId(submoduleDir)
              //TODO recursion until parent
              def qualifiedModuleName = "${parentArtifactId}:${artifactId}"
              qualifiedNames[artifactId] = qualifiedModuleName
              if (artifactId != submoduleDir) {
                artifactIdToDir[qualifiedModuleName] = "${submoduleDir.parentFile.name}/${submoduleDir.name}"
              }
              modules.append("'${qualifiedModuleName}', ")
            }
          }
        }
      }
      def strLength = modules.length()
      if (strLength > 2) {
        modules.delete(strLength - 2, strLength)
      }
    }
    File settingsFile = new File("settings.gradle")
    if (settingsFile.exists()) {
      print "(backing up existing one)... "
      settingsFile.renameTo(new File("settings.gradle.bak"))
    }
    def settingsText = "${projectName ?: ''} ${modules.length() > 0 ? "include ${modules.toString()}" : ''}"
    artifactIdToDir.each {entry ->
      //TODO fucking escaping
      settingsText += """
project(':$entry.key').projectDir = """ + '"$rootDir/' + "${entry.value}" + '" as File'
    }
    settingsFile.text = prettyPrint(settingsText)
    return qualifiedNames
  }

  String geEffectiveContents(String file, String[] args) {
//TODO work on output stream, without writing to file
    def fileName = "effective-${file}.xml"
    print "Wait, obtaining effective $file... "

    def ant = new AntBuilder()   // create an antbuilder
    ant.exec(outputproperty: "cmdOut",
            errorproperty: "cmdErr",
            failonerror: "true",
            executable: ((String) System.properties['os.name']).toLowerCase().contains("win") ? "mvn.bat" : "mvn") {
      arg(line: """-Doutput=${fileName} org.apache.maven.plugins:maven-help-plugin:2.2-SNAPSHOT:effective-$file""")
      env(key: "JAVA_HOME", value: System.getProperty("java.home"))
    }

//print the output if verbose flag is on
    println((args.any {it.equals("-verbose")}) ? "\n ${ant.project.properties.cmdOut}" : "Done.")

// read in the effective pom file
    File tmpFile = new File(fileName);
    if (!args.any {it.equals("-keepFile")}) {
      tmpFile.deleteOnExit()
    }
    // get it's text into a string
    return tmpFile.text
  }

/**
 * complex print statement does one extra task which is
 * iterate over each <exclusion> node and print out the artifact id.
 * It also tackles the properties attached to dependencies
 */
  private def createComplexDependency(it, build, scope, Map dependencyProperties) {
    build.append("${scope}(\"${contructSignature(it)}\") {\n")
    it.exclusions.exclusion.each() {
      build.append("exclude(module: '${it.artifactId}')\n")
    }
    if (dependencyProperties) {
      dependencyProperties.keySet().each { key ->
        build.append("$key : ${dependencyProperties.get(key)}\n")
      }
    }
    build.append("}\n")
  }

/**
 * Print out the basic form og gradle dependency
 */
  private def createBasicDependency(mavenDependency, build, String scope) {
    def classifier = contructSignature(mavenDependency)
    build.append("${scope} \"${classifier}\"\n")
  }
/**
 * Print out the basic form og gradle dependency
 */
  private def createProjectDependency(mavenDependency, build, String scope) {
    if (mavenDependency.type == 'war') {
      dependentWars.add mavenDependency
    }
    //TODO support nested projects
    build.append("${scope} project(':${qualifiedNames[mavenDependency.artifactId.text()] ?: mavenDependency.artifactId}')\n")
  }

/**
 * Construct and return the signature of a dependency, including it's version and
 * classifier if it exists
 */
  private def contructSignature(mavenDependency) {
    def gradelDep = "${mavenDependency.groupId.text()}:${mavenDependency.artifactId.text()}:${mavenDependency?.version?.text()}"
    def classifier = elementHasText(mavenDependency.classifier) ? gradelDep + ":" + mavenDependency.classifier.text().trim() : gradelDep
    return classifier
  }

/**
 * Check to see if the selected node has content
 */
  private boolean elementHasText(it) {
    return it.text().length() != 0
  }
}