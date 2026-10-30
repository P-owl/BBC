# BBC

무현버스 직업 모드와 도시 능력자 서버 모드팩 개발 저장소. 두 프로젝트는 서로 독립이다.

| 폴더 | 내용 |
| --- | --- |
| `duel-mod/` | 무현버스(코블몬) 서버용 duel 모드 — Fabric 1.21.1 |
| `city-ability/` | 도시 능력자 서버 모드 (bbc) — Fabric 1.21.1, 코블몬 없음 |

## jar 받기 (빌드 안 해도 됨)
코드가 올라오면 GitHub가 자동으로 빌드해서 Releases에 올린다.
- 도시 능력자: https://github.com/P-owl/BBC/releases/tag/city-latest
- 무현버스 duel: https://github.com/P-owl/BBC/releases/tag/duel-latest

빌드 진행 상황은 저장소의 **Actions** 탭. 초록 체크면 성공.

## 직접 빌드하기 (Windows, 필요할 때만)
1. GitHub Desktop에서 이 저장소를 Clone (처음 한 번).
2. 새 작업이 올라오면 GitHub Desktop에서 **Fetch origin → Pull origin**.
3. 빌드할 프로젝트 폴더(`duel-mod` 또는 `city-ability`)에서 `gradlew.bat build` 실행.
4. 결과 jar: 그 폴더의 `build/libs/`.
