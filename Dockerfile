# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9.16-eclipse-temurin-17
ARG TOMCAT_IMAGE=tomcat:9.0.121-jre17-temurin-noble

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
COPY vendor ./vendor
RUN mvn -B -ntp install:install-file \
        -Dfile=vendor/maven/klab/ilc/cnr/it/OntoApi/1.0/OntoApi-1.0.jar \
        -DpomFile=vendor/maven/klab/ilc/cnr/it/OntoApi/1.0/OntoApi-1.0.pom \
        -DgeneratePom=false \
    && mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

FROM ${TOMCAT_IMAGE} AS runtime

ARG LEXO_UID=10001
ARG LEXO_GID=10001

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid "${LEXO_GID}" lexo \
    && useradd --system --uid "${LEXO_UID}" --gid lexo \
        --home-dir "${CATALINA_HOME}" --shell /usr/sbin/nologin lexo \
    && rm -rf "${CATALINA_HOME}/webapps/"* \
    && mkdir -p /var/lib/lexo /var/log/lexo \
    && chown -R lexo:lexo "${CATALINA_HOME}" /var/lib/lexo /var/log/lexo

COPY --from=build --chown=lexo:lexo \
    /workspace/target/LexO-server.war \
    ${CATALINA_HOME}/webapps/LexO-server.war

ENV CATALINA_OPTS="-Djava.awt.headless=true -Dlexo.log.dir=/var/log/lexo -Dlexo.log.console.level=INFO"

USER lexo:lexo
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=8 \
    CMD curl --fail --silent --show-error \
        http://127.0.0.1:8080/LexO-server/service/health/ready || exit 1

CMD ["catalina.sh", "run"]
