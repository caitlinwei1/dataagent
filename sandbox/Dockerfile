# Python sandbox image for DataAgent's Docker code pool (replaces the 3GB+ continuumio/anaconda3 default).
# pandas pinned below 3.0: LLM-generated analysis code still uses 2.x APIs such as to_numeric(errors="ignore").
# Behind an HTTPS proxy: docker build --network host --build-arg HTTPS_PROXY=$HTTPS_PROXY \
#   --secret id=ca,src=/path/to/ca.crt -t gbi-python-sandbox:0.1 sandbox/
FROM python:3.11-slim
ARG HTTPS_PROXY
RUN --mount=type=secret,id=ca,required=false,target=/tmp/ca.crt \
    if [ -f /tmp/ca.crt ]; then export PIP_CERT=/tmp/ca.crt; fi; \
    HTTPS_PROXY=$HTTPS_PROXY pip install --no-cache-dir \
      "pandas>=2.2,<2.3" "numpy<2.1" "scipy<1.15" "matplotlib<3.10" "scikit-learn<1.6" "statsmodels<0.15"
RUN useradd -m -u 10001 sandbox
USER sandbox
WORKDIR /home/sandbox
