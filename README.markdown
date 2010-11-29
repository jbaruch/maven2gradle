This tool obtains  the effective pom of the current project, reads its dependencies and generates build.gradle scripts. It also generates settings.gradle for multimodule builds.
It currently supports both single-module and multi-module POMs, inheritance, dependency management, properties - everything.

Installation:
=============
* Download the latest jar from [JFrog Artifactory repository](http://repo.jfrog.org/artifactory/repo/org/gradle/tools/maven2gradle/1.0-SNAPSHOT/)
* Put the jar into $GRADLE_HOME/lib
* Put the batch files from sources bin directory into $GRADLE_HOME/bin

Usage:
============
* Run maven2gradle batch in the root of the converted project

 Available options:
 -----------------
*   -verbose prints the obtained effective pom
*   -keepFile saves the obtained effective pom

Building from source:
=====================
* You'll need a gradle.properties file, as described here: http://gradle.org/0.9-rc-3/docs/userguide/userguide_single.html#sec:repositories
Look at 33.5.2.1 "Accessing password protected Maven repositories", example 33.24. In short: create gradle.properties file in your ~\.gradle and define USER and PASSWORD properties.

Note: Your project will be considered multi-module only if your reactor is also a parent of at least one of your modules. Why so? Reactor project is built last, when Parent project is built first. I need reactor to be built first, because effective-pom Mojo generates needed output only if it founds modules in first project it encounters. Making reactor also a parent achieves this.

[Antony Stubbs](http://github.com/astubbs)

[Baruch Sadogursky](http://github.com/jbaruch)
