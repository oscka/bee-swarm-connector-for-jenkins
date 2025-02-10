FROM maven:3-openjdk-8

WORKDIR /opt/bee-swarm-connector-for-jenkins
COPY . .

RUN mvn clean package
