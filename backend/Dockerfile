FROM 10.25.13.206:5000/devops/eclipse-temurin:21-jre
WORKDIR /app
RUN sed -i 's@http://archive.ubuntu.com@https://mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list \
&& sed -i 's@http://security.ubuntu.com@https://mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list \
&& DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=20 update \
&& DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=5 -o Acquire::http::Timeout=20 install -y --no-install-recommends libopenslide0 \
&& rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/medical-report-platform-0.1.0.jar app.jar
RUN mkdir -p /data/reports
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
