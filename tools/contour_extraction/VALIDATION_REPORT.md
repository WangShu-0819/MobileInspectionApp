# Alignment guide validation (2026-09-02)

Validation output: `%TEMP%\alignment_round_2` (temporary; formal
`alignment_guide_output` was not replaced).

The final automatic gate reports 11 `READY` and 19 `RECAPTURE_REQUIRED`.
Visual review further marks five automatic `READY` results unsafe. They must
not be promoted to production guides until a general single-part separation
method can distinguish connected black parts.

| Image | Final review | Reason |
| --- | --- | --- |
| 103109 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103112 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103115 | GOOD | Closed central-part outline |
| 103118 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103122 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103127 | WRONG_PART | Several connected assembly regions are merged |
| 103130 | RECAPTURE_REQUIRED | Large concave region bridges multiple parts |
| 103134 | WRONG_PART | Adjacent connected regions are merged |
| 103138 | WRONG_PART | Multiple brackets are treated as one part |
| 103145 | RECAPTURE_REQUIRED | Central part cannot be isolated reliably |
| 103149 | GOOD | Closed single-plate outline |
| 103156 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103203 | RECAPTURE_REQUIRED | Large concave region bridges multiple parts |
| 103205 | RECAPTURE_REQUIRED | Large concave region bridges multiple parts |
| 103209 | GOOD | Closed central plate with sparse guides |
| 103218 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103223 | WRONG_PART | Outline includes connected neighboring structure |
| 103228 | RECAPTURE_REQUIRED | Foreground beam and rear bracket are connected |
| 103231 | RECAPTURE_REQUIRED | Large concave region bridges multiple parts |
| 103241 | RECAPTURE_REQUIRED | Reflective/overlapping parts cannot be isolated |
| 103308 | GOOD | Closed central bracket outline |
| 103313 | RECAPTURE_REQUIRED | Multiple adjacent parts would be merged |
| 103318 | GOOD | Closed central circular part outline |
| 103322 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103326 | RECAPTURE_REQUIRED | Large concave region bridges multiple parts |
| 103331 | RECAPTURE_REQUIRED | Multiple adjacent parts would be merged |
| 103335 | WRONG_PART | Several connected assembly regions are merged |
| 103340 | RECAPTURE_REQUIRED | No reliable isolated central part |
| 103344 | GOOD | Closed central circular part outline |
| 103354 | RECAPTURE_REQUIRED | No reliable isolated central part |

## Result

- Visually safe guides: 6/30.
- Unsafe automatic `READY` results: 5/30.
- Safe automatic rejection: 19/30.
- Unit tests: 11/11 passed.
- The formal completion criteria are not met.

The remaining boundary is not edge sensitivity. In these photographs, several
black physical parts touch or occlude one another and form one connected image
region. Canny/closing/contour geometry alone has no general cue that identifies
which side of such a junction belongs to the intended physical part. Further
threshold tuning caused overlapping behavior between correct concave parts and
incorrect merged parts, so iteration stopped instead of adding dataset-specific
branches.
