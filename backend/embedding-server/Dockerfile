FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY requirements-observability.txt .
RUN pip install --no-cache-dir -r requirements-observability.txt
# 런타임 첫 요청 전에 전이 의존성 호환성을 이미지 빌드 단계에서 검증한다.
RUN python -c "from FlagEmbedding import BGEM3FlagModel"

COPY main.py .

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
