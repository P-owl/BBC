# BBC

무현버스 직업 모드와 도시 능력자 서버 모드팩 개발 저장소. 두 프로젝트는 서로 독립이다.

| 폴더 | 내용 |
| --- | --- |
| `duel-mod/` | 무현버스(코블몬) 서버용 duel 모드 — Fabric 1.21.1 |
| `city-ability/` | 도시 능력자 서버 모드 (bbc) — Fabric 1.21.1, 코블몬 없음 |

## 받아서 빌드하기 (Windows)
1. GitHub Desktop에서 이 저장소를 Clone (처음 한 번).
2. 새 작업이 올라오면 GitHub Desktop에서 **Fetch origin → Pull origin**.
3. 빌드할 프로젝트 폴더(`duel-mod` 또는 `city-ability`)에서 `gradlew.bat build` 실행.
4. 결과 jar: 그 폴더의 `build/libs/`.
