FROM httpd:2.4

COPY httpd-pdownload.conf /usr/local/apache2/conf/extra/httpd-pdownload.conf
COPY sample.txt /usr/local/apache2/htdocs/sample.txt

RUN printf '\n# pdownload custom config\nIncludeOptional conf/extra/httpd-pdownload.conf\n' >> /usr/local/apache2/conf/httpd.conf
