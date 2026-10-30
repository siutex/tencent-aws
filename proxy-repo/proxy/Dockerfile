# Build the jars first:  mvn -pl proxy -am package
# Then:                  docker buildx build --platform linux/amd64 proxy
# Pin the base image by digest before production (see docs/WDROZENIE.md).
FROM public.ecr.aws/amazoncorretto/amazoncorretto:25
WORKDIR /app
# Dependencies change rarely: separate layer.
COPY target/lib/ /app/lib/
COPY target/origin-proxy.jar /app/origin-proxy.jar
USER 10001:10001
EXPOSE 8080
# Read-only root filesystem on ECS: no perf data files, exit (and get replaced) on OOM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:-UsePerfData", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/origin-proxy.jar"]
