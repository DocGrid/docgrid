# HWP/HWPX 파서 시험 자료

이 디렉터리의 네 파일은 `neolord0/hwplib` 및 `neolord0/hwpxlib` 저장소에서 제공한 Apache-2.0 샘플입니다. 사용 목적은 본문·표 셀 텍스트 추출과 빈 파일 오류 검증이며, 실제 사용자 문서는 포함하지 않습니다.
Apache-2.0 라이선스 사본은 저장소 루트의 `LICENSE`에 있으며, 아래 원본 링크와 SHA-256으로 샘플 출처를 확인할 수 있습니다.

| 로컬 파일 | 원본과 커밋 | SHA-256 |
| --- | --- | --- |
| `table.hwp` | [`hwplib/sample_hwp/basic/표.hwp`](https://github.com/neolord0/hwplib/blob/6746c27f17ebf5277493206284aa32044a1839c4/sample_hwp/basic/%ED%91%9C.hwp) · `6746c27f17ebf5277493206284aa32044a1839c4` | `ed97e2136e53cbb09ab6362d5631f6f1e4f752a5e53f09e85e4bc9c19f9e6d35` |
| `blank.hwp` | [`hwplib/sample_hwp/basic/blank.hwp`](https://github.com/neolord0/hwplib/blob/6746c27f17ebf5277493206284aa32044a1839c4/sample_hwp/basic/blank.hwp) · 같은 커밋 | `08550eb806392423df94d3ad39fd3cc031fa25f13aeb458f0f1382553800cb02` |
| `table.hwpx` | [`hwpxlib/testFile/tool/textextractor/Table.hwpx`](https://github.com/neolord0/hwpxlib/blob/f9fd2255ac0fc57414e0b657d115e7de51d31c65/testFile/tool/textextractor/Table.hwpx) · `f9fd2255ac0fc57414e0b657d115e7de51d31c65` | `af13702617b11bc080d4c069578fcb3b6a7aa6142590cacc72507cbd6324ea41` |
| `blank.hwpx` | [`hwpxlib/testFile/tool/blank.hwpx`](https://github.com/neolord0/hwpxlib/blob/f9fd2255ac0fc57414e0b657d115e7de51d31c65/testFile/tool/blank.hwpx) · 같은 커밋 | `d28f55cd622b6d0cade2d8ae3b5d53f1ed5c4154289e463a4c390d9be157aa6d` |

- [hwplib 라이선스 원문](https://github.com/neolord0/hwplib/blob/6746c27f17ebf5277493206284aa32044a1839c4/license.txt)
- [hwpxlib 라이선스 원문](https://github.com/neolord0/hwpxlib/blob/f9fd2255ac0fc57414e0b657d115e7de51d31c65/license.txt)

손상 파일과 암호화 표시 시험은 이 파일의 복사본을 시험 중 메모리에서 변형해 수행합니다. 테스트가 원본 fixture를 덮어쓰지 않습니다.
