# THIRD_PARTY — 프론트엔드 오픈소스 사용 내역

DocGrid 프론트엔드(`frontend/`)가 사용하는 오픈소스 구성요소와 라이선스를 정리한 문서입니다.
DocGrid 자체 소스코드와 문서는 [Apache License 2.0](LICENSE)에 따라 배포합니다.

## 작성 기준

- **기준일**: 2026-10-26
- **대상**: `frontend/package-lock.json`에 잠긴 npm 의존성 전체(전이 의존성 포함) 692개. 백엔드(Java)·임베딩 서버(Python)·모델·컨테이너 이미지는 [README의 라이선스 절](README.md#라이선스-및-외부-구성요소)에 따로 정리돼 있습니다.
- **출처**: 오픈소스 라이선스 검증 결과 `준법성현황_DocGrid_frontend.xlsx`(심사 측 스캔)의 구성요소·버전·라이선스·홈페이지 정보를 기준으로 하고, 패키지 정식 이름(`@scope/name`)과 SPDX 라이선스 표기는 `package-lock.json`으로 대조했습니다.
- **충돌 여부**: 심사 측 스캔에서 라이선스 충돌은 발견되지 않았습니다. 이 문서는 그 결과를 옮겨 적은 것이며, 별도의 법률 검토가 아닙니다.
- **정리 방식**: 같은 패키지의 여러 버전은 한 줄에 모았고, 플랫폼별로 나뉜 네이티브 패키지(`lightningcss-linux-x64-gnu` 등)는 `이름-*` 한 줄로 묶었습니다.

## 라이선스 유형별 요약

| 라이선스 | 구성요소 수 | 비고 |
|---|---:|---|
| MIT | 571 | |
| Apache-2.0 | 45 | |
| MPL-2.0 | 28 | 약한 카피레프트. 아래 참조 |
| ISC | 15 | |
| BSD-2-Clause | 11 | |
| BSD-3-Clause | 6 | |
| LGPL-3.0-or-later | 10 | 약한 카피레프트. 아래 참조 |
| CC0-1.0 | 2 | `language-subtag-registry`, `core`(speed-highlight) |
| 0BSD | 1 | |
| BlueOak-1.0.0 | 1 | `minimatch` |
| Python-2.0 | 1 | `argparse` |
| CC-BY-4.0 | 1 | `caniuse-lite` (데이터) |
| **합계** | **692** | 심사 xlsx 기준 분류 |

## 제약 조건이 있는 라이선스

심사 측 스캔에서 "소스코드 공개 필요"로 분류된 38개(MPL-2.0 28개, LGPL-3.0-or-later 10개)입니다. 모두 수정하지 않고 npm으로 받아 쓰는 의존성입니다.

| 구성요소 | 라이선스 | 설명 |
|---|---|---|
| `@img/sharp-libvips-*` (10개) | LGPL-3.0-or-later | 이미지 처리 라이브러리 sharp가 쓰는 libvips 네이티브 바이너리. 플랫폼별 패키지이며 `package-lock.json`에서 `dev`·`optional`로 표시됩니다. |
| `@img/sharp-win32-*`, `@img/sharp-wasm32` | Apache-2.0 AND LGPL-3.0-or-later (wasm32는 `AND MIT` 추가) | 심사 xlsx에는 Apache-2.0으로만 기재돼 있으나 `package-lock.json` 메타데이터는 LGPL-3.0-or-later를 함께 표기합니다. 위 libvips와 같은 계열로 보고 함께 고지합니다. |
| `lightningcss`, `lightningcss-*` (24개) | MPL-2.0 | CSS 변환기(Tailwind·Vite가 빌드에 사용). 플랫폼별 네이티브 패키지. 1.31.1과 1.32.0 두 버전이 설치돼 있습니다. |
| `axe-core` | MPL-2.0 | 접근성 검사 엔진(eslint-plugin-jsx-a11y 의존). |
| `satori`, `@vercel/og`, `@resvg/resvg-wasm` | MPL-2.0 | 이미지 생성 계열. 빌드 도구의 전이 의존성입니다. |

- 위 패키지는 `package-lock.json` 기준으로 모두 `dev` 의존성이며, 프론트엔드의 `dependencies`는 `react`, `react-dom`, `drizzle-orm`(과 전이 의존성 `scheduler`) 4개뿐입니다. 다만 빌드 산출물에 이 패키지의 코드가 포함되는지는 확인하지 못했습니다.
- MPL-2.0·LGPL-3.0은 해당 패키지 자체를 수정해 배포할 때 그 부분의 소스 공개 의무가 생기는 라이선스입니다. DocGrid는 이 패키지들의 소스를 수정하지 않았습니다.
- 각 라이선스 원문은 해당 패키지의 npm 배포본(`LICENSE`)과 저장소에서 확인할 수 있습니다.

## 구성요소 전체 목록

`비고`에는 `frontend/package.json`에 직접 선언한 의존성만 표시했습니다. 나머지는 전이 의존성입니다. 저장소 열은 심사 스캔의 홈페이지 정보이며, GitHub 주소는 `소유자/저장소`로 줄여 적었습니다. `-`는 스캔 결과에 홈페이지 정보가 없는 항목입니다.

| 컴포넌트 | 버전 | 라이선스 | 저장소 | 비고 |
|---------|------|---------|--------|------|
| @alloc/quick-lru | 5.2.0 | MIT | sindresorhus/quick-lru |  |
| @babel/code-frame | 7.29.0 | MIT | babel/babel |  |
| @babel/compat-data | 7.29.3 | MIT | babel/babel |  |
| @babel/core | 7.29.0 | MIT | babel/babel |  |
| @babel/generator | 7.29.1 | MIT | babel/babel |  |
| @babel/helper-compilation-targets | 7.28.6 | MIT | babel/babel |  |
| @babel/helper-globals | 7.28.0 | MIT | babel/babel |  |
| @babel/helper-module-imports | 7.28.6 | MIT | babel/babel |  |
| @babel/helper-module-transforms | 7.28.6 | MIT | babel/babel |  |
| @babel/helper-string-parser | 7.27.1 | MIT | babel/babel |  |
| @babel/helper-validator-identifier | 7.28.5 | MIT | babel/babel |  |
| @babel/helper-validator-option | 7.27.1 | MIT | babel/babel |  |
| @babel/helpers | 7.29.2 | MIT | babel/babel |  |
| @babel/parser | 7.29.3 | MIT | https://babel.dev/docs/en/next/babel-parser |  |
| @babel/template | 7.28.6 | MIT | babel/babel |  |
| @babel/traverse | 7.29.0 | MIT | babel/babel |  |
| @babel/types | 7.29.0 | MIT | babel/babel |  |
| @cloudflare/kv-asset-handler | 0.5.0 | MIT | cloudflare/workers-sdk |  |
| @cloudflare/unenv-preset | 2.16.1 | MIT | cloudflare/workers-sdk |  |
| @cloudflare/vite-plugin | 1.37.1 | MIT | cloudflare/workers-sdk | 직접 의존성(devDependencies) |
| @cloudflare/workerd-* | 1.20260515.1 | Apache-2.0 | cloudflare/workerd | 플랫폼별 패키지 4개 |
| @cloudflare/workerd-windows-64 | 1.20260515.1 | Apache-2.0 | cloudflare/workerd |  |
| @cspotcode/source-map-support | 0.8.1 | MIT | cspotcode/node-source-map-support |  |
| @drizzle-team/brocli | 0.10.2 | Apache-2.0 | - |  |
| @emnapi/core | 1.8.1, 1.10.0 | MIT | toyobayashi/emnapi |  |
| @emnapi/runtime | 1.8.1, 1.10.0 | MIT | toyobayashi/emnapi |  |
| @emnapi/wasi-threads | 1.1.0, 1.2.1 | MIT | toyobayashi/emnapi |  |
| @esbuild-kit/core-utils | 3.3.2 | MIT | esbuild-kit/core-utils |  |
| @esbuild-kit/esm-loader | 2.6.5 | MIT | esbuild-kit/esm-loader |  |
| @esbuild/aix-ppc64 | 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/android-arm | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/android-arm64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/android-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/darwin-arm64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/darwin-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/freebsd-arm64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/freebsd-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-arm | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-arm64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-ia32 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-loong64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-mips64el | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-ppc64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-riscv64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-s390x | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/linux-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/netbsd-arm64 | 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/netbsd-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/openbsd-arm64 | 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/openbsd-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/openharmony-arm64 | 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/sunos-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/win32-arm64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/win32-ia32 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @esbuild/win32-x64 | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| @eslint-community/eslint-utils | 4.9.1 | MIT | eslint-community/eslint-utils |  |
| @eslint-community/regexpp | 4.12.2 | MIT | eslint-community/regexpp |  |
| @eslint/config-array | 0.21.2 | Apache-2.0 | eslint/rewrite |  |
| @eslint/config-helpers | 0.4.2 | Apache-2.0 | eslint/rewrite |  |
| @eslint/core | 0.17.0 | Apache-2.0 | eslint/rewrite |  |
| @eslint/eslintrc | 3.3.5 | MIT | eslint/eslintrc |  |
| @eslint/js | 9.39.4 | MIT | eslint/eslint | 직접 의존성(devDependencies) |
| @eslint/object-schema | 2.1.7 | Apache-2.0 | eslint/rewrite |  |
| @eslint/plugin-kit | 0.4.1 | Apache-2.0 | eslint/rewrite |  |
| @humanfs/core | 0.19.2 | Apache-2.0 | humanwhocodes/humanfs |  |
| @humanfs/node | 0.16.8 | Apache-2.0 | humanwhocodes/humanfs |  |
| @humanfs/types | 0.15.0 | Apache-2.0 | humanwhocodes/humanfs |  |
| @humanwhocodes/module-importer | 1.0.1 | Apache-2.0 | humanwhocodes/module-importer |  |
| @humanwhocodes/retry | 0.4.3 | Apache-2.0 | humanwhocodes/retry |  |
| @img/colour | 1.1.0 | MIT | lovell/colour |  |
| @img/sharp-* | 0.34.5 | Apache-2.0 | https://sharp.pixelplumbing.com | 플랫폼별 패키지 10개 |
| @img/sharp-libvips-* | 1.2.4 | LGPL-3.0-or-later | https://sharp.pixelplumbing.com | 플랫폼별 패키지 10개 |
| @img/sharp-wasm32 | 0.34.5 | Apache-2.0 AND LGPL-3.0-or-later AND MIT | https://sharp.pixelplumbing.com | 심사 xlsx에는 Apache-2.0으로 기재됨. package-lock 메타데이터에는 LGPL-3.0-or-later가 함께 표기돼 있어 그 표기를 따름 |
| @img/sharp-win32-* | 0.34.5 | Apache-2.0 AND LGPL-3.0-or-later | https://sharp.pixelplumbing.com | 심사 xlsx에는 Apache-2.0으로 기재됨. package-lock 메타데이터에는 LGPL-3.0-or-later가 함께 표기돼 있어 그 표기를 따름 |
| @jridgewell/gen-mapping | 0.3.13 | MIT | jridgewell/sourcemaps |  |
| @jridgewell/remapping | 2.3.5 | MIT | jridgewell/sourcemaps |  |
| @jridgewell/resolve-uri | 3.1.2 | MIT | jridgewell/resolve-uri |  |
| @jridgewell/source-map | 0.3.11 | MIT | jridgewell/sourcemaps |  |
| @jridgewell/sourcemap-codec | 1.5.5 | MIT | jridgewell/sourcemaps |  |
| @jridgewell/trace-mapping | 0.3.9, 0.3.31 | MIT | jridgewell/sourcemaps |  |
| @napi-rs/wasm-runtime | 1.1.1, 1.1.4 | MIT | napi-rs/napi-rs |  |
| @next/eslint-plugin-next | 16.2.6 | MIT | vercel/next.js | 직접 의존성(devDependencies) |
| @nodelib/fs.scandir | 2.1.5 | MIT | https://github.com/nodelib/nodelib/tree/master/packages/fs/fs.scandir |  |
| @nodelib/fs.stat | 2.0.5 | MIT | https://github.com/nodelib/nodelib/tree/master/packages/fs/fs.stat |  |
| @nodelib/fs.walk | 1.2.8 | MIT | https://github.com/nodelib/nodelib/tree/master/packages/fs/fs.walk |  |
| @oxc-project/types | 0.130.0, 0.153.0 | MIT | oxc-project/oxc |  |
| @poppinss/colors | 4.1.6 | MIT | poppinss/colors |  |
| @poppinss/dumper | 0.6.5 | MIT | poppinss/dumper |  |
| @poppinss/exception | 1.2.3 | MIT | poppinss/exception |  |
| @resvg/resvg-wasm | 2.4.0 | MPL-2.0 | yisibl/resvg-js |  |
| @rolldown/binding-* | 1.0.1, 1.2.13 | MIT | rolldown/rolldown | 플랫폼별 패키지 30개 |
| @rolldown/pluginutils | 1.0.0-rc.18, 1.0.1 | MIT | https://github.com/rolldown/plugins/tree/main/packages/pluginutils#readme |  |
| @shuding/opentype.js | 1.4.0-beta.0 | MIT | opentypejs/opentype.js |  |
| @sindresorhus/is | 7.2.0 | MIT | sindresorhus/is |  |
| @speed-highlight/core | 1.2.15 | CC0-1.0 | speed-highlight/core |  |
| @tailwindcss/node | 4.2.1, 4.3.3 | MIT | tailwindlabs/tailwindcss |  |
| @tailwindcss/oxide | 4.2.1, 4.3.3 | MIT | tailwindlabs/tailwindcss |  |
| @tailwindcss/oxide-* | 4.2.1, 4.3.3 | MIT | tailwindlabs/tailwindcss | 플랫폼별 패키지 24개 |
| @tailwindcss/postcss | 4.2.1 | MIT | tailwindlabs/tailwindcss | 직접 의존성(devDependencies) |
| @tailwindcss/vite | 4.3.3 | MIT | tailwindlabs/tailwindcss | 직접 의존성(devDependencies) |
| @tybys/wasm-util | 0.10.1, 0.10.2 | MIT | toyobayashi/wasm-util |  |
| @types/eslint | 9.6.1 | MIT | DefinitelyTyped/DefinitelyTyped |  |
| @types/eslint-scope | 3.7.7 | MIT | DefinitelyTyped/DefinitelyTyped |  |
| @types/estree | 1.0.9 | MIT | https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/estree |  |
| @types/json-schema | 7.0.15 | BSD-3-Clause | kriszyp/json-schema |  |
| @types/node | 22.19.19 | MIT | DefinitelyTyped/DefinitelyTyped | 직접 의존성(devDependencies) |
| @types/react | 19.2.14 | MIT | DefinitelyTyped/DefinitelyTyped | 직접 의존성(devDependencies) |
| @types/react-dom | 19.2.3 | MIT | DefinitelyTyped/DefinitelyTyped | 직접 의존성(devDependencies) |
| @typescript-eslint/eslint-plugin | 8.59.3 | MIT | typescript-eslint/typescript-eslint |  |
| @typescript-eslint/parser | 8.59.3 | MIT | - |  |
| @typescript-eslint/project-service | 8.59.3 | MIT | - |  |
| @typescript-eslint/scope-manager | 8.59.3 | MIT | - |  |
| @typescript-eslint/tsconfig-utils | 8.59.3 | MIT | typescript-eslint/typescript-eslint |  |
| @typescript-eslint/type-utils | 8.59.3 | MIT | - |  |
| @typescript-eslint/types | 8.59.3 | MIT | typescript-eslint/typescript-eslint |  |
| @typescript-eslint/typescript-estree | 8.59.3 | MIT | - |  |
| @typescript-eslint/utils | 8.59.3 | MIT | typescript-eslint/typescript-eslint |  |
| @typescript-eslint/visitor-keys | 8.59.3 | MIT | - |  |
| @unpic/core | 1.0.3 | MIT | ascorbic/unpic-img |  |
| @unpic/react | 1.0.2 | MIT | ascorbic/unpic-img |  |
| @vercel/og | 0.8.6 | MPL-2.0 | vercel/og |  |
| @vinext/types | 1.0.0-beta.2 | MIT | cloudflare/vinext |  |
| @vitejs/plugin-react | 6.0.2 | MIT | vitejs/vite-plugin-react | 직접 의존성(devDependencies) |
| @vitejs/plugin-rsc | 0.5.26 | MIT | vitejs/vite-plugin-react | 직접 의존성(devDependencies) |
| @webassemblyjs/ast | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/floating-point-hex-parser | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/helper-api-error | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/helper-buffer | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/helper-numbers | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/helper-wasm-bytecode | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/helper-wasm-section | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/ieee754 | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/leb128 | 1.13.2 | Apache-2.0 | xtuc/webassemblyjs |  |
| @webassemblyjs/utf8 | 1.13.2 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/wasm-edit | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/wasm-gen | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/wasm-opt | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/wasm-parser | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @webassemblyjs/wast-printer | 1.14.1 | MIT | xtuc/webassemblyjs |  |
| @xtuc/ieee754 | 1.2.0 | BSD-3-Clause | feross/ieee754 |  |
| @xtuc/long | 4.2.2 | Apache-2.0 | dcodeIO/long.js |  |
| acorn | 8.16.0 | MIT | acornjs/acorn |  |
| acorn-import-phases | 1.0.4 | MIT | nicolo-ribaudo/acorn-import-phases |  |
| acorn-jsx | 5.3.2 | MIT | acornjs/acorn-jsx |  |
| acorn-loose | 8.5.2 | MIT | - |  |
| ajv | 6.15.0, 8.20.0 | MIT | ajv-validator/ajv |  |
| ajv-formats | 2.1.1 | MIT | ajv-validator/ajv-formats |  |
| ajv-keywords | 5.1.0 | MIT | epoberezkin/ajv-keywords |  |
| ansi-styles | 4.3.0 | MIT | chalk/ansi-styles |  |
| argparse | 2.0.1 | Python-2.0 | nodeca/argparse |  |
| aria-query | 5.3.2 | Apache-2.0 | A11yance/aria-query |  |
| array-buffer-byte-length | 1.0.2 | MIT | inspect-js/array-buffer-byte-length |  |
| array-includes | 3.1.9 | MIT | es-shims/array-includes |  |
| array.prototype.findlast | 1.2.5 | MIT | es-shims/Array.prototype.findLast |  |
| array.prototype.flat | 1.3.3 | MIT | es-shims/Array.prototype.flat |  |
| array.prototype.flatmap | 1.3.3 | MIT | es-shims/Array.prototype.flatMap |  |
| array.prototype.tosorted | 1.1.4 | MIT | es-shims/Array.prototype.toSorted |  |
| arraybuffer.prototype.slice | 1.0.4 | MIT | es-shims/ArrayBuffer.prototype.slice |  |
| ast-types-flow | 0.0.8 | MIT | kyldvs/ast-types-flow |  |
| async-function | 1.0.0 | MIT | ljharb/async-function |  |
| available-typed-arrays | 1.0.7 | MIT | inspect-js/available-typed-arrays |  |
| axe-core | 4.11.4 | MPL-2.0 | dequelabs/axe-core |  |
| axobject-query | 4.1.0 | Apache-2.0 | A11yance/axobject-query |  |
| balanced-match | 1.0.2, 4.0.4 | MIT | juliangruber/balanced-match |  |
| base64-js | 0.0.8 | MIT | https://www.npmjs.com/package/js-base64 |  |
| baseline-browser-mapping | 2.10.30 | Apache-2.0 | web-platform-dx/baseline-browser-mapping |  |
| blake3-wasm | 2.1.5 | MIT | connor4312/blake3 |  |
| brace-expansion | 1.1.14, 5.0.6 | MIT | juliangruber/brace-expansion |  |
| braces | 3.0.3 | MIT | micromatch/braces |  |
| browserslist | 4.28.2 | MIT | browserslist/browserslist |  |
| buffer-from | 1.1.2 | MIT | LinusU/buffer-from |  |
| call-bind | 1.0.9 | MIT | ljharb/call-bind |  |
| call-bind-apply-helpers | 1.0.2 | MIT | ljharb/call-bind-apply-helpers |  |
| call-bound | 1.0.4 | MIT | ljharb/call-bound |  |
| callsites | 3.1.0 | MIT | sindresorhus/callsites |  |
| camelize | 1.0.1 | MIT | ljharb/camelize |  |
| caniuse-lite | 1.0.30001793 | CC-BY-4.0 | browserslist/caniuse-lite |  |
| chalk | 4.1.2 | MIT | chalk/chalk |  |
| chrome-trace-event | 1.0.4 | MIT | samccone/chrome-trace-event |  |
| color-convert | 2.0.1 | MIT | Qix-/color-convert |  |
| color-name | 1.1.4 | MIT | colorjs/color-name |  |
| commander | 2.20.3 | MIT | tj/commander.js |  |
| concat-map | 0.0.1 | MIT | ljharb/concat-map |  |
| consola | 3.4.2 | MIT | unjs/consola |  |
| convert-source-map | 2.0.0 | MIT | thlorenz/convert-source-map |  |
| cookie | 1.1.1 | MIT | jshttp/cookie |  |
| cross-spawn | 7.0.6 | MIT | moxystudio/node-cross-spawn |  |
| crossws | 0.4.15 | MIT | h3js/crossws |  |
| css-background-parser | 0.1.0 | MIT | gilmoreorless/css-background-parser |  |
| css-box-shadow | 1.0.0-3 | MIT | https://www.npmjs.com/package//css-box-shadow |  |
| css-color-keywords | 1.0.0 | ISC | sonicdoe/css-color-keywords |  |
| css-gradient-parser | 0.0.16 | MIT | https://www.npmjs.com/package/css-gradient-parser |  |
| css-to-react-native | 3.2.0 | MIT | styled-components/css-to-react-native |  |
| csstype | 3.2.3 | MIT | frenic/csstype |  |
| damerau-levenshtein | 1.0.8 | BSD-2-Clause | tad-lispy/node-damerau-levenshtein |  |
| data-view-buffer | 1.0.2 | MIT | inspect-js/data-view-buffer |  |
| data-view-byte-length | 1.0.2 | MIT | inspect-js/data-view-byte-length |  |
| data-view-byte-offset | 1.0.1 | MIT | inspect-js/data-view-byte-offset |  |
| db0 | 0.4.1 | MIT | unjs/db0 |  |
| debug | 4.4.3 | MIT | debug-js/debug |  |
| deep-is | 0.1.4 | MIT | thlorenz/deep-is |  |
| define-data-property | 1.1.4 | MIT | ljharb/define-data-property |  |
| define-properties | 1.2.1 | MIT | ljharb/define-properties |  |
| detect-libc | 2.1.2 | Apache-2.0 | lovell/detect-libc |  |
| doctrine | 2.1.0 | Apache-2.0 | eslint/doctrine |  |
| drizzle-kit | 0.31.10 | MIT | drizzle-team/drizzle-orm | 직접 의존성(devDependencies): 마이그레이션 생성 |
| drizzle-orm | 0.45.2 | Apache-2.0 | - | 직접 의존성(dependencies): DB 쿼리 빌더 |
| dunder-proto | 1.0.1 | MIT | es-shims/dunder-proto |  |
| electron-to-chromium | 1.5.358 | ISC | Kilian/electron-to-chromium |  |
| emoji-regex | 9.2.2 | MIT | mathiasbynens/emoji-regex |  |
| emoji-regex-xs | 2.0.1 | MIT | slevithan/emoji-regex-xs |  |
| enhanced-resolve | 5.26.0 | MIT | webpack/enhanced-resolve |  |
| env-runner | 0.2.3 | MIT | unjs/env-runner |  |
| error-stack-parser-es | 1.0.5 | MIT | antfu/error-stack-parser-es |  |
| es-abstract | 1.24.2 | MIT | ljharb/es-abstract |  |
| es-define-property | 1.0.1 | MIT | ljharb/es-define-property |  |
| es-errors | 1.3.0 | MIT | ljharb/es-errors |  |
| es-iterator-helpers | 1.3.2 | MIT | es-shims/iterator-helpers |  |
| es-module-lexer | 1.7.0, 2.1.0 | MIT | guybedford/es-module-lexer |  |
| es-object-atoms | 1.1.1 | MIT | ljharb/es-object-atoms |  |
| es-set-tostringtag | 2.1.0 | MIT | es-shims/es-set-tostringtag |  |
| es-shim-unscopables | 1.1.0 | MIT | ljharb/es-shim-unscopables |  |
| es-to-primitive | 1.3.0 | MIT | ljharb/es-to-primitive |  |
| esbuild | 0.18.20, 0.25.12, 0.27.3, 0.28.0 | MIT | evanw/esbuild |  |
| escalade | 3.2.0 | MIT | lukeed/escalade |  |
| escape-html | 1.0.3 | MIT | component/escape-html |  |
| escape-string-regexp | 4.0.0 | MIT | sindresorhus/escape-string-regexp |  |
| eslint | 9.39.4 | MIT | eslint/eslint | 직접 의존성(devDependencies): 린트 |
| eslint-plugin-jsx-a11y | 6.10.2 | MIT | jsx-eslint/eslint-plugin-jsx-a11y | 직접 의존성(devDependencies) |
| eslint-plugin-react | 7.37.5 | MIT | jsx-eslint/eslint-plugin-react | 직접 의존성(devDependencies) |
| eslint-plugin-react-hooks | 7.1.1 | MIT | https://react.dev/ | 직접 의존성(devDependencies) |
| eslint-scope | 5.1.1, 8.4.0 | BSD-2-Clause | eslint/js |  |
| eslint-visitor-keys | 3.4.3, 4.2.1, 5.0.1 | Apache-2.0 | eslint/eslint-visitor-keys |  |
| espree | 10.4.0 | BSD-2-Clause | eslint/js |  |
| esquery | 1.7.0 | BSD-3-Clause | estools/esquery |  |
| esrecurse | 4.3.0 | BSD-2-Clause | estools/esrecurse |  |
| estraverse | 4.3.0, 5.3.0 | BSD-2-Clause | estools/estraverse |  |
| estree-walker | 3.0.3 | MIT | Rich-Harris/estree-walker |  |
| esutils | 2.0.3 | BSD-2-Clause | estools/esutils |  |
| events | 3.3.0 | MIT | Gozala/events |  |
| exsolve | 1.1.3 | MIT | unjs/exsolve |  |
| fast-deep-equal | 3.1.3 | MIT | epoberezkin/fast-deep-equal |  |
| fast-glob | 3.3.1, 3.3.3 | MIT | mrmlnc/fast-glob |  |
| fast-json-stable-stringify | 2.1.0 | MIT | epoberezkin/fast-json-stable-stringify |  |
| fast-levenshtein | 2.0.6 | MIT | hiddentao/fast-levenshtein |  |
| fast-uri | 3.1.2 | BSD-3-Clause | fastify/fast-uri |  |
| fastq | 1.20.1 | ISC | mcollina/fastq |  |
| fdir | 6.5.0 | MIT | thecodrr/fdir |  |
| fflate | 0.7.4 | MIT | 101arrowz/fflate |  |
| file-entry-cache | 8.0.0 | MIT | jaredwray/file-entry-cache |  |
| fill-range | 7.1.1 | MIT | jonschlinkert/fill-range |  |
| find-up | 5.0.0 | MIT | sindresorhus/find-up |  |
| flat-cache | 4.0.1 | MIT | jaredwray/flat-cache |  |
| flatted | 3.4.2 | ISC | WebReflection/flatted |  |
| for-each | 0.3.5 | MIT | Raynos/for-each |  |
| fsevents | 2.3.3 | MIT | fsevents/fsevents |  |
| function-bind | 1.1.2 | MIT | Raynos/function-bind |  |
| function.prototype.name | 1.1.8 | MIT | es-shims/Function.prototype.name |  |
| functions-have-names | 1.2.3 | MIT | inspect-js/functions-have-names |  |
| generator-function | 2.0.1 | MIT | TimothyGu/generator-function |  |
| gensync | 1.0.0-beta.2 | MIT | loganfsmyth/gensync |  |
| get-intrinsic | 1.3.0 | MIT | ljharb/get-intrinsic |  |
| get-proto | 1.0.1 | MIT | ljharb/get-proto |  |
| get-symbol-description | 1.1.0 | MIT | inspect-js/get-symbol-description |  |
| get-tsconfig | 4.14.0 | MIT | privatenumber/get-tsconfig |  |
| glob-parent | 5.1.2, 6.0.2 | ISC | gulpjs/glob-parent |  |
| glob-to-regexp | 0.4.1 | BSD-2-Clause | fitzgen/glob-to-regexp |  |
| globals | 14.0.0, 16.4.0 | MIT | sindresorhus/globals | 직접 의존성(devDependencies) |
| globalthis | 1.0.4 | MIT | ljharb/System.global |  |
| gopd | 1.2.0 | MIT | ljharb/gopd |  |
| graceful-fs | 4.2.11 | ISC | isaacs/node-graceful-fs |  |
| h3 | 2.0.2 | MIT | h3js/h3 |  |
| has-bigints | 1.1.0 | MIT | ljharb/has-bigints |  |
| has-flag | 4.0.0 | MIT | sindresorhus/has-flag |  |
| has-property-descriptors | 1.0.2 | MIT | inspect-js/has-property-descriptors |  |
| has-proto | 1.2.0 | MIT | inspect-js/has-proto |  |
| has-symbols | 1.1.0 | MIT | inspect-js/has-symbols |  |
| has-tostringtag | 1.0.2 | MIT | inspect-js/has-tostringtag |  |
| hasown | 2.0.3 | MIT | inspect-js/hasOwn |  |
| hermes-estree | 0.25.1 | MIT | ook/hermes |  |
| hermes-parser | 0.25.1 | MIT | ook/hermes |  |
| hex-rgb | 4.3.0 | MIT | sindresorhus/hex-rgb |  |
| hookable | 6.1.2 | MIT | unjs/hookable |  |
| httpxy | 0.5.5 | MIT | unjs/httpxy |  |
| ignore | 5.3.2, 7.0.5 | MIT | kaelzhang/node-ignore |  |
| image-size | 2.0.2 | MIT | image-size/image-size |  |
| import-fresh | 3.3.1 | MIT | sindresorhus/import-fresh |  |
| imurmurhash | 0.1.4 | MIT | jensyt/imurmurhash-js |  |
| internal-slot | 1.1.0 | MIT | ljharb/internal-slot |  |
| ipaddr.js | 2.4.0 | MIT | whitequark/ipaddr.js |  |
| is-array-buffer | 3.0.5 | MIT | inspect-js/is-array-buffer |  |
| is-async-function | 2.1.1 | MIT | inspect-js/is-async-function |  |
| is-bigint | 1.1.0 | MIT | inspect-js/is-bigint |  |
| is-boolean-object | 1.2.2 | MIT | inspect-js/is-boolean-object |  |
| is-callable | 1.2.7 | MIT | inspect-js/is-callable |  |
| is-core-module | 2.16.2 | MIT | inspect-js/is-core-module |  |
| is-data-view | 1.0.2 | MIT | inspect-js/is-data-view |  |
| is-date-object | 1.1.0 | MIT | inspect-js/is-date-object |  |
| is-extglob | 2.1.1 | MIT | micromatch/is-extglob |  |
| is-finalizationregistry | 1.1.1 | MIT | inspect-js/is-finalizationregistry |  |
| is-generator-function | 1.1.2 | MIT | inspect-js/is-generator-function |  |
| is-glob | 4.0.3 | MIT | micromatch/is-glob |  |
| is-map | 2.0.3 | MIT | inspect-js/is-map |  |
| is-negative-zero | 2.0.3 | MIT | inspect-js/is-negative-zero |  |
| is-number | 7.0.0 | MIT | jonschlinkert/is-number |  |
| is-number-object | 1.1.1 | MIT | inspect-js/is-number-object |  |
| is-regex | 1.2.1 | MIT | inspect-js/is-regex |  |
| is-set | 2.0.3 | MIT | inspect-js/is-set |  |
| is-shared-array-buffer | 1.0.4 | MIT | inspect-js/is-shared-array-buffer |  |
| is-string | 1.1.1 | MIT | inspect-js/is-string |  |
| is-symbol | 1.1.1 | MIT | inspect-js/is-symbol |  |
| is-typed-array | 1.1.15 | MIT | inspect-js/is-typed-array |  |
| is-weakmap | 2.0.2 | MIT | inspect-js/is-weakmap |  |
| is-weakref | 1.1.1 | MIT | inspect-js/is-weakref |  |
| is-weakset | 2.0.4 | MIT | inspect-js/is-weakset |  |
| isarray | 2.0.5 | MIT | juliangruber/isarray |  |
| isexe | 2.0.0 | ISC | isaacs/isexe |  |
| iterator.prototype | 1.1.5 | MIT | ljharb/Iterator.prototype |  |
| jest-worker | 27.5.1 | MIT | facebook/jest |  |
| jiti | 2.7.0 | MIT | unjs/jiti |  |
| js-tokens | 4.0.0, 9.0.1 | MIT | lydell/js-tokens |  |
| js-yaml | 4.1.1 | MIT | nodeca/js-yaml |  |
| jsesc | 3.1.0 | MIT | mathiasbynens/jsesc |  |
| json-buffer | 3.0.1 | MIT | dominictarr/json-buffer |  |
| json-schema-traverse | 0.4.1, 1.0.0 | MIT | epoberezkin/json-schema-traverse |  |
| json-stable-stringify-without-jsonify | 1.0.1 | MIT | samn/json-stable-stringify |  |
| json5 | 2.2.3 | MIT | json5/json5 |  |
| jsx-ast-utils | 3.3.5 | MIT | jsx-eslint/jsx-ast-utils |  |
| keyv | 4.5.4 | MIT | jaredwray/keyv |  |
| kleur | 4.1.5 | MIT | lukeed/kleur |  |
| language-subtag-registry | 0.3.23 | CC0-1.0 | mattcg/language-subtag-registry |  |
| language-tags | 1.0.9 | MIT | mattcg/language-tags |  |
| levn | 0.4.1 | MIT | gkz/levn |  |
| lightningcss | 1.31.1, 1.32.0 | MPL-2.0 | parcel-bundler/lightningcss |  |
| lightningcss-* | 1.31.1, 1.32.0 | MPL-2.0 | parcel-bundler/lightningcss | 플랫폼별 패키지 22개 |
| linebreak | 1.1.0 | MIT | - |  |
| loader-runner | 4.3.2 | MIT | webpack/loader-runner |  |
| locate-path | 6.0.0 | MIT | sindresorhus/locate-path |  |
| lodash.merge | 4.6.2 | MIT | lodash/lodash |  |
| loose-envify | 1.4.0 | MIT | zertosh/loose-envify |  |
| lru-cache | 5.1.1 | ISC | isaacs/node-lru-cache |  |
| magic-string | 0.30.21 | MIT | Rich-Harris/magic-string |  |
| math-intrinsics | 1.1.0 | MIT | es-shims/math-intrinsics |  |
| merge-stream | 2.0.0 | MIT | grncdr/merge-stream |  |
| merge2 | 1.4.1 | MIT | teambition/merge2 |  |
| micromatch | 4.0.8 | MIT | micromatch/micromatch |  |
| mime-db | 1.54.0 | MIT | jshttp/mime-db |  |
| miniflare | 4.20260515.0 | MIT | cloudflare/workers-sdk |  |
| minimatch | 3.1.5 | ISC | isaacs/minimatch |  |
| minimatch | 10.2.5 | BlueOak-1.0.0 | isaacs/minimatch |  |
| ms | 2.1.3 | MIT | vercel/ms |  |
| nanoid | 3.3.12 | MIT | ai/nanoid |  |
| natural-compare | 1.4.0 | MIT | litejs/natural-compare-lite |  |
| neo-async | 2.6.2 | MIT | suguru03/neo-async |  |
| nf3 | 0.3.24 | MIT | unjs/nf3 |  |
| nitro | 3.0.260903-beta | MIT | nitrojs/nitro | 직접 의존성(devDependencies): 서버 런타임 |
| node-exports-info | 1.6.0 | MIT | inspect-js/node-exports-info |  |
| node-releases | 2.0.44 | MIT | chicoxyzzy/node-releases |  |
| object-assign | 4.1.1 | MIT | sindresorhus/object-assign |  |
| object-inspect | 1.13.4 | MIT | inspect-js/object-inspect |  |
| object-keys | 1.1.1 | MIT | ljharb/object-keys |  |
| object.assign | 4.1.7 | MIT | ljharb/object.assign |  |
| object.entries | 1.1.9 | MIT | es-shims/Object.entries |  |
| object.fromentries | 2.0.8 | MIT | es-shims/Object.fromEntries |  |
| object.values | 1.2.1 | MIT | es-shims/Object.values |  |
| ocache | 0.3.0 | MIT | unjs/ocache |  |
| optionator | 0.9.4 | MIT | gkz/optionator |  |
| own-keys | 1.0.1 | MIT | ljharb/own-keys |  |
| p-limit | 3.1.0 | MIT | sindresorhus/p-limit |  |
| p-locate | 5.0.0 | MIT | sindresorhus/p-locate |  |
| pako | 0.2.9 | MIT | nodeca/pako |  |
| parent-module | 1.0.1 | MIT | sindresorhus/parent-module |  |
| parse-css-color | 0.2.1 | MIT | noeldelgado/parse-css-color |  |
| path-exists | 4.0.0 | MIT | sindresorhus/path-exists |  |
| path-key | 3.1.1 | MIT | sindresorhus/path-key |  |
| path-parse | 1.0.7 | MIT | jbgutierrez/path-parse |  |
| path-to-regexp | 6.3.0 | MIT | pillarjs/path-to-regexp |  |
| pathe | 2.0.3 | MIT | unjs/pathe |  |
| picocolors | 1.1.1 | ISC | alexeyraspopov/picocolors |  |
| picomatch | 2.3.2, 4.0.4 | MIT | micromatch/picomatch |  |
| possible-typed-array-names | 1.1.0 | MIT | ljharb/possible-typed-array-names |  |
| postcss | 8.5.14 | MIT | postcss/postcss |  |
| postcss-value-parser | 4.2.0 | MIT | TrySound/postcss-value-parser |  |
| prelude-ls | 1.2.1 | MIT | gkz/prelude-ls |  |
| prop-types | 15.8.1 | MIT | facebook/prop-types |  |
| punycode | 2.3.1 | MIT | mathiasbynens/punycode.js |  |
| queue-microtask | 1.2.3 | MIT | feross/queue-microtask |  |
| react | 19.2.6 | MIT | https://react.dev/ | 직접 의존성(dependencies): UI 라이브러리 |
| react-dom | 19.2.6 | MIT | - | 직접 의존성(dependencies): UI 렌더러 |
| react-is | 16.13.1 | MIT | facebook/react |  |
| react-server-dom-webpack | 19.2.6 | MIT | facebook/react | 직접 의존성(devDependencies) |
| reflect.getprototypeof | 1.0.10 | MIT | es-shims/Reflect.getPrototypeOf |  |
| regexp.prototype.flags | 1.5.4 | MIT | es-shims/RegExp.prototype.flags |  |
| require-from-string | 2.0.2 | MIT | floatdrop/require-from-string |  |
| resolve | 2.0.0-next.7 | MIT | browserify/resolve |  |
| resolve-from | 4.0.0 | MIT | sindresorhus/resolve-from |  |
| resolve-pkg-maps | 1.0.0 | MIT | privatenumber/resolve-pkg-maps |  |
| reusify | 1.1.0 | MIT | mcollina/reusify |  |
| rolldown | 1.0.1, 1.2.13 | MIT | rolldown/rolldown |  |
| rou3 | 0.9.2, 1.0.0 | MIT | h3js/rou3 |  |
| run-parallel | 1.2.0 | MIT | feross/run-parallel |  |
| safe-array-concat | 1.1.4 | MIT | ljharb/safe-array-concat |  |
| safe-push-apply | 1.0.0 | MIT | ljharb/safe-push-apply |  |
| safe-regex-test | 1.1.0 | MIT | ljharb/safe-regex-test |  |
| satori | 0.16.0 | MPL-2.0 | - |  |
| scheduler | 0.27.0 | MIT | ook/react |  |
| schema-utils | 4.3.3 | MIT | webpack/schema-utils |  |
| semver | 6.3.1, 7.8.0 | ISC | npm/node-semver |  |
| set-function-length | 1.2.2 | MIT | ljharb/set-function-length |  |
| set-function-name | 2.0.2 | MIT | ljharb/set-function-name |  |
| set-proto | 1.0.0 | MIT | ljharb/set-proto |  |
| sharp | 0.34.5 | Apache-2.0 | https://sharp.pixelplumbing.com |  |
| shebang-command | 2.0.0 | MIT | kevva/shebang-command |  |
| shebang-regex | 3.0.0 | MIT | sindresorhus/shebang-regex |  |
| side-channel | 1.1.0 | MIT | ljharb/side-channel |  |
| side-channel-list | 1.0.1 | MIT | ljharb/side-channel-list |  |
| side-channel-map | 1.0.1 | MIT | ljharb/side-channel-map |  |
| side-channel-weakmap | 1.0.2 | MIT | ljharb/side-channel-weakmap |  |
| source-map | 0.6.1 | BSD-3-Clause | mozilla/source-map |  |
| source-map-js | 1.2.1 | BSD-3-Clause | 7rulnik/source-map-js |  |
| source-map-support | 0.5.21 | MIT | evanw/node-source-map-support |  |
| srvx | 0.11.15, 1.0.5 | MIT | h3js/srvx |  |
| stop-iteration-iterator | 1.1.0 | MIT | ljharb/stop-iteration-iterator |  |
| string.prototype.codepointat | 0.2.1 | MIT | https://www.npmjs.com/package/string.prototype.codepointat |  |
| string.prototype.includes | 2.0.1 | MIT | mathiasbynens/String.prototype.includes |  |
| string.prototype.matchall | 4.0.12 | MIT | es-shims/String.prototype.matchAll |  |
| string.prototype.repeat | 1.0.0 | MIT | mathiasbynens/String.prototype.repeat |  |
| string.prototype.trim | 1.2.10 | MIT | es-shims/String.prototype.trim |  |
| string.prototype.trimend | 1.0.9 | MIT | es-shims/String.prototype.trimEnd |  |
| string.prototype.trimstart | 1.0.8 | MIT | es-shims/String.prototype.trimStart |  |
| strip-json-comments | 3.1.1 | MIT | sindresorhus/strip-json-comments |  |
| strip-literal | 3.1.0 | MIT | antfu/strip-literal |  |
| supports-color | 7.2.0, 8.1.1, 10.2.2 | MIT | chalk/supports-color |  |
| supports-preserve-symlinks-flag | 1.0.0 | MIT | inspect-js/node-supports-preserve-symlinks-flag |  |
| tailwindcss | 4.2.1, 4.3.3 | MIT | tailwindlabs/tailwindcss | 직접 의존성(devDependencies): CSS 프레임워크 |
| tapable | 2.3.3 | MIT | webpack/tapable |  |
| terser | 5.47.1 | BSD-2-Clause | https://terser.org |  |
| terser-webpack-plugin | 5.6.0 | MIT | webpack/minimizer-webpack-plugin |  |
| tiny-inflate | 1.0.3 | MIT | https://www.npmjs.com/package/tiny-inflate |  |
| tinyglobby | 0.2.16 | MIT | SuperchupuDev/tinyglobby |  |
| to-regex-range | 5.0.1 | MIT | micromatch/to-regex-range |  |
| ts-api-utils | 2.5.0 | MIT | JoshuaKGoldberg/ts-api-utils |  |
| tslib | 2.8.1 | 0BSD | Microsoft/tslib |  |
| tsx | 4.22.1 | MIT | privatenumber/tsx |  |
| turbo-stream | 3.2.0 | MIT | jacob-ebey/turbo-stream |  |
| type-check | 0.4.0 | MIT | gkz/type-check |  |
| typed-array-buffer | 1.0.3 | MIT | inspect-js/typed-array-buffer |  |
| typed-array-byte-length | 1.0.3 | MIT | inspect-js/typed-array-byte-length |  |
| typed-array-byte-offset | 1.0.4 | MIT | inspect-js/typed-array-byte-offset |  |
| typed-array-length | 1.0.7 | MIT | inspect-js/typed-array-length |  |
| typescript | 5.9.3 | Apache-2.0 | microsoft/TypeScript | 직접 의존성(devDependencies): 타입 검사 |
| typescript-eslint | 8.59.3 | MIT | typescript-eslint/typescript-eslint | 직접 의존성(devDependencies) |
| unbox-primitive | 1.1.0 | MIT | ljharb/unbox-primitive |  |
| undici | 7.24.8 | MIT | nodejs/undici |  |
| undici-types | 6.21.0 | MIT | nodejs/undici |  |
| unenv | 2.0.0-rc.24 | MIT | unjs/unenv |  |
| unicode-trie | 2.0.0 | MIT | https://www.npmjs.com/package/@cto.af/unicode-trie |  |
| unpic | 4.2.2 | MIT | - |  |
| unstorage | 2.0.0-alpha.10 | MIT | unjs/unstorage |  |
| update-browserslist-db | 1.2.3 | MIT | browserslist/update-db |  |
| uri-js | 4.4.1 | BSD-2-Clause | garycourt/uri-js |  |
| vinext | 1.0.0-beta.2 | MIT | cloudflare/vinext | 직접 의존성(devDependencies): Next.js 호환 빌드·실행 도구 |
| vite | 8.0.13 | MIT | vitejs/vite | 직접 의존성(devDependencies): 번들러 |
| vite-plugin-commonjs | 0.10.4 | MIT | vite-plugin/vite-plugin-commonjs |  |
| vite-plugin-dynamic-import | 1.6.0 | MIT | vite-plugin/vite-plugin-dynamic-import |  |
| vitefu | 1.1.3 | MIT | svitejs/vitefu |  |
| watchpack | 2.5.1 | MIT | webpack/watchpack |  |
| web-vitals | 4.2.4 | Apache-2.0 | GoogleChrome/web-vitals |  |
| webpack | 5.106.2 | MIT | webpack/webpack |  |
| webpack-sources | 3.4.1 | MIT | webpack/webpack-sources |  |
| which | 2.0.2 | ISC | isaacs/node-which |  |
| which-boxed-primitive | 1.1.1 | MIT | inspect-js/which-boxed-primitive |  |
| which-builtin-type | 1.2.1 | MIT | inspect-js/which-builtin-type |  |
| which-collection | 1.0.2 | MIT | inspect-js/which-collection |  |
| which-typed-array | 1.1.20 | MIT | inspect-js/which-typed-array |  |
| word-wrap | 1.2.5 | MIT | jonschlinkert/word-wrap |  |
| workerd | 1.20260515.1 | Apache-2.0 | cloudflare/workerd |  |
| wrangler | 4.92.0 | MIT | cloudflare/workers-sdk | 직접 의존성(devDependencies): Cloudflare Workers 도구 |
| ws | 8.18.0 | MIT | websockets/ws |  |
| yallist | 3.1.1 | ISC | isaacs/yallist |  |
| yocto-queue | 0.1.0 | MIT | sindresorhus/yocto-queue |  |
| yoga-layout | 3.2.1 | MIT | facebook/yoga |  |
| youch | 4.1.0-beta.10 | MIT | poppinss/youch |  |
| youch-core | 0.3.3 | MIT | poppinss/youch-core |  |
| zod | 4.4.3 | MIT | https://zod.dev |  |
| zod-validation-error | 4.0.2 | MIT | causaly/zod-validation-error |  |
