# Important Note

This repo is obsolete and has been superseded by the [Gradle Build Setup Plugin[(http://www.gradle.org/docs/current/userguide/bootstrap_plugin.html) instead.



----

This tool obtains  the effective pom of the current project, reads its dependencies and generates build.gradle scripts.

Features list:
==============
* Uses effective pom and effective settings (support for pom inheritance, dependency management, properties)
* Supports both single module and multimodule projects
* Generates settings.gradle for multimodule projects
* Supports custom module names (that differ from directory names)
* Generates general metadata - id, description and version
* Applies maven, java and war plugins (as needed)
* Supports packaging war projects as jars if needed
* Generates dependencies (both external and inter-module)
* Generates download repositories (inc. local maven repository)
* Adjusts java compiler settings
* Supports packaging of sources and tests
* Supports testng runner
* Generates global exclusions from Maven enforcer plugin settings

To do:
=================
* Generate deployment settings (with support to optional dependencies and provided scope in generated pom.xml)

Installation:
=============
* Note: Current version only works with Gradle 1.0-RC-Milestone3
* Note: Current version only works with Maven 3.0+ (see FAQ for the Maven 2 exception. Contribute a pull request if you can solve it.)
* Option 1:
    * Download the latest jar from [JFrog Artifactory repository](http://repo.jfrog.org/artifactory/repo/org/gradle/tools/maven2gradle/1.0-SNAPSHOT/)
    * Put the jar into $GRADLE_HOME/lib
    * Put the batch files from sources bin directory into $GRADLE_HOME/bin
* Option 2:
    * Run the `gradle installScripts` task which automatically copies the latest JAR and scripts into the aforementioned directories.

Usage:
============
* Run maven2gradle batch in the root of the converted project

 Available options:
 -----------------
*   -verbose prints the obtained effective pom
*   -keepFile saves the obtained effective pom

Building from source:
=====================
* Option 1:
    * If wishing to only build locally, there are no special requirements for building.
* Option 2:
    * If wishing to deploy the resultant JAR and Source, you'll need a gradle.properties file, as described here: http://gradle.org/0.9-rc-3/docs/userguide/userguide_single.html#sec:repositories
    * Look at 33.5.2.1 "Accessing password protected Maven repositories", example 33.24.
    * Create gradle.properties file in your ~\.gradle and define USER and PASSWORD properties.
    * Trigger the use of this file and values by setting `-Dupload=true` on the command line invocation of the Gradle build.

Note: Your project will be considered multi-module only if your reactor is also a parent of at least one of your modules. Why so? Reactor project is built last, when Parent project is built first. I need reactor to be built first, because effective-pom Mojo generates needed output only if it founds modules in first project it encounters. Making reactor also a parent achieves this.

Recognized Contributors:
============
* [Antony Stubbs](http://github.com/astubbs)
* [Baruch Sadogursky](http://github.com/jbaruch)
* [Matthew McCullough](http://github.com/matthewmccullough)
