FROM docker.elastic.co/elasticsearch/elasticsearch:7.2.0

COPY build/distributions/*.zip /usr/share/elasticsearch/external/plugins/
RUN ./bin/elasticsearch-plugin list | \
  grep -q fast-cosine-similarity || \
  ./bin/elasticsearch-plugin install \
    file:///usr/share/elasticsearch/external/plugins/fast-cosine-similarity-7.2.0.zip
