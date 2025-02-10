# Bee Swarm Jenkins Connector For Jenkins

## overview

Plugin for use with Bee swarms in Jenkins

[Jenkins Dev Docs](https://www.jenkins.io/doc/developer/)

## prerequisite

Develop Requirement

- Settings in pom.xml
- Current Value
  - Java 8 / Jenkins 289.1

Production Requirement

- Java 8 ~ / Jenkins 2.289.1 ~

## build & run

Run/Debug Configuration -> Run -> clean hpi:run -Djetty.port={PORT}

## version history

1.1.0 : add swarm sleep logic / javapath setting

1.2.0 : modify rest -> graphql

1.3.0 : add swarm disable, enable function

1.3.4 : Bug fix for java 17, 21
