# 도시 능력자 모드 (bbc)

Fabric 1.21.1. 코블몬 없이 단독으로 돈다. 기획은 claude.ai의 "도시 능력자 서버 — 기획안" 문서.

## 빌드
`city-ability` 폴더에서 `gradlew.bat build` → `build/libs/bbc-city-ability-<버전>.jar`

## 지금 들어 있는 것 (0단계)
| 부분 | 파일 | 내용 |
| --- | --- | --- |
| 저장 | `core/PlayerData` | 플레이어별 직업·자유 데이터 + 서버 전체 기록 (`bbc_players.dat`) |
| 직업 틀 | `job/JobType`, `job/Jobs` | 직업 등록·바꾸기·등급 |
| 기술 | `job/JobSkills`, `job/SkillMenu` | 직업 기술 풀에서 5개를 Z·X·C·V·B 칸에 장착 |
| 자원 | `job/Stamina` | 스태미나 + 전투 모드 보스바 |
| 전투 모드 | `combat/CombatMode` | ` 키로 켜고 끔, 액션바에 칸 상태 |
| 키 | `client/BbcClient`, `net/*` | 전투 키(Z~B, G, R, H, T) 전송 |
| 시험 직업 | `job/TraineeJob` | 수련생 (기술 6개, 확인용 — 나중에 지움) |

## 시험해 보기 (싱글 또는 테스트 서버)
1. `/bbc admin job <내 이름> trainee`
2. `` ` `` 키로 전투 모드 → Z·X·C·V·B 로 기술
3. `/bbc skills` 로 장착 칸 바꾸기, `/bbc admin rank <내 이름> 2` 하면 6번째 기술 잠금 해제
