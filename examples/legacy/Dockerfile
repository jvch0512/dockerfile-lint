# Old service image. Synthetic example.
FROM ubuntu:latest
ENV DB_PASSWORD=hunter2
ARG API_TOKEN
ADD https://example.test/tool.tar.gz /opt/
ADD config.yml /etc/app/
RUN apt-get update && apt-get install -y curl openssh-server
RUN curl -fsSL https://example.test/install.sh | bash
RUN curl -k https://internal.example.test/ca.pem -o /etc/ca.pem \
    && chmod -R 777 /var/app
EXPOSE 22 8080
CMD ["/usr/sbin/sshd", "-D"]
