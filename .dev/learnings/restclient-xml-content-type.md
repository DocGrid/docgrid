# Classpath에 jackson-dataformat-xml이 있으면 RestClient가 XML을 보낸다

## 문제

`RestClient.builder()`(Spring Boot 자동 구성 Builder가 아닌 정적 Builder)로 만든 Client가 Content-Type을 지정하지 않고
`.body(record)`를 보내면, Classpath에 `jackson-dataformat-xml`이 있을 때 XML 변환기가 JSON보다 먼저 선택된다.
`google-cloud-storage`(GCS 어댑터, fefe7a9)가 이 라이브러리를 끌고 들어온다. 로컬 HttpServer로 확인하면
`Content-Type: application/xml;charset=UTF-8`이 전송되고, 실제 Embedding Server는 422로 거부한다.

## 적용 패턴

외부 JSON API를 호출하는 Client는 Builder에 `defaultHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)`를 두거나 요청마다
`contentType(APPLICATION_JSON)`을 명시한다. Embedding Client의 두 호출은 같은 날 팀원이 `develop`에 먼저 반영했고(`32ccc58`), Ollama Client의 호출은 #404 브랜치에서 `.contentType(MediaType.APPLICATION_JSON)`을 명시해 고쳤다.

## 테스트 패턴

`RestClient`를 mock으로 대체한 단위 테스트는 전송 형식을 볼 수 없어서 이 회귀를 잡지 못했다. 운영 설정 클래스
(`EmbeddingServerConfig`, `OllamaServerConfig`)로 만든 실제 RestClient를 로컬 `com.sun.net.httpserver.HttpServer`에 연결해
받은 Content-Type과 본문을 단언한다 (`EmbeddingClientWireContractTest`, `OllamaClientWireContractTest`).
Ollama는 요청이 거부돼도 추출형 Fallback으로 대체돼 증상이 드러나지 않으므로 이런 전송 계약 테스트가 더 중요하다.
