-- 武汉市第一医院 2026 年脱敏模拟数据
-- 默认范围：2026-01-01 至 2026-08-30（含首尾），每天 300 条，共 72,600 条。
-- 本脚本可重复执行：标准医疗表按 (source_system, source_id) 更新；
-- 数字切片仅重建 metadata-only/wuhan-first-hospital/2026/ 前缀的模拟记录。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE medical_report;

SET @whfh_start_date = DATE('2026-01-01');
SET @whfh_end_date = DATE('2026-08-30');
SET @whfh_daily_volume = 300;
SET @whfh_total_rows = (DATEDIFF(@whfh_end_date, @whfh_start_date) + 1) * @whfh_daily_volume;
SET @whfh_source_system = 'WHFH_HIS';

-- 采集后会按 patient_no 执行跨记录质检；为大批量演示数据补索引，避免逐条全表扫描。
SET @whfh_patient_no_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='idx_patient_no'
);
SET @whfh_index_sql = IF(@whfh_patient_no_index_exists=0,
  'ALTER TABLE patient ADD INDEX idx_patient_no(patient_no)',
  'DO 1');
PREPARE whfh_index_stmt FROM @whfh_index_sql;
EXECUTE whfh_index_stmt;
DEALLOCATE PREPARE whfh_index_stmt;

DROP TEMPORARY TABLE IF EXISTS tmp_whfh_seq;
CREATE TEMPORARY TABLE tmp_whfh_seq (
  n INT NOT NULL PRIMARY KEY,
  event_time DATETIME NOT NULL,
  INDEX idx_tmp_whfh_event_time(event_time)
) ENGINE=InnoDB;

INSERT INTO tmp_whfh_seq(n,event_time)
SELECT number + 1,
       DATE_ADD(
         DATE_ADD(@whfh_start_date, INTERVAL FLOOR(number / @whfh_daily_volume) DAY),
         INTERVAL (28800 + MOD(number,@whfh_daily_volume) * 120) SECOND
       )
FROM (
  SELECT ones.n + tens.n*10 + hundreds.n*100 + thousands.n*1000 + ten_thousands.n*10000 AS number
  FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
  CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
  CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) numbers
WHERE number < @whfh_total_rows;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_his_patient (
  id VARCHAR(40) PRIMARY KEY,
  patient_no VARCHAR(100), patient_name VARCHAR(100), sex_code VARCHAR(10), birthday DATE, age INT,
  id_card VARCHAR(50), phone VARCHAR(50), update_time DATETIME NOT NULL,
  INDEX idx_whfh_patient_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_his_visit (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_no VARCHAR(100), visit_type VARCHAR(20),
  department_code VARCHAR(50), department_name VARCHAR(100), doctor_code VARCHAR(50), doctor_name VARCHAR(100),
  admission_time DATETIME, discharge_time DATETIME, update_time DATETIME NOT NULL,
  INDEX idx_whfh_visit_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_emr_diagnosis (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_source_id VARCHAR(40),
  diagnosis_code VARCHAR(50), diagnosis_name VARCHAR(255), diagnosis_type VARCHAR(30), diagnosis_time DATETIME,
  update_time DATETIME NOT NULL, INDEX idx_whfh_diagnosis_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_lis_result (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_source_id VARCHAR(40),
  item_code VARCHAR(50), item_name VARCHAR(255), result_value VARCHAR(100), result_unit VARCHAR(50),
  reference_range VARCHAR(100), abnormal_flag VARCHAR(20), result_time DATETIME,
  update_time DATETIME NOT NULL, INDEX idx_whfh_lab_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_pacs_exam (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_source_id VARCHAR(40),
  exam_code VARCHAR(50), exam_name VARCHAR(255), exam_part VARCHAR(100), exam_result TEXT, exam_conclusion TEXT,
  exam_time DATETIME, update_time DATETIME NOT NULL, INDEX idx_whfh_exam_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_his_operation (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_source_id VARCHAR(40),
  operation_code VARCHAR(50), operation_name VARCHAR(255), operation_time DATETIME, operator_name VARCHAR(100),
  update_time DATETIME NOT NULL, INDEX idx_whfh_operation_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mock_hospital.whfh_his_medication (
  id VARCHAR(40) PRIMARY KEY, patient_source_id VARCHAR(40), visit_source_id VARCHAR(40),
  drug_code VARCHAR(50), drug_name VARCHAR(255), dosage VARCHAR(50), unit VARCHAR(30), frequency VARCHAR(50),
  route VARCHAR(50), start_time DATETIME, end_time DATETIME,
  update_time DATETIME NOT NULL, INDEX idx_whfh_medication_update(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 兼容早期已由 MySQL 8 默认 0900 排序规则创建的模拟源表。
ALTER TABLE mock_hospital.whfh_his_patient CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_his_visit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_emr_diagnosis CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_lis_result CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_pacs_exam CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_his_operation CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mock_hospital.whfh_his_medication CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 正式标准数据：固定算法生成，便于复现与验收。
INSERT INTO patient(source_system,source_id,patient_no,name,gender,birthday,age,id_card,phone,quality_status)
SELECT @whfh_source_system,
       CONCAT('WHFH-P-',LPAD(n,8,'0')),
       CONCAT('WH',DATE_FORMAT(event_time,'%Y%m%d'),LPAD(MOD(n-1,@whfh_daily_volume)+1,3,'0')),
       CONCAT(
         ELT(MOD(CRC32(CONCAT('surname',n)),20)+1,'王','李','张','刘','陈','杨','黄','赵','周','吴','徐','孙','胡','朱','高','林','何','郭','马','罗'),
         ELT(MOD(CRC32(CONCAT('given',n)),24)+1,'伟','芳','娜','敏','静','磊','军','洋','勇','艳','杰','娟','涛','超','明','霞','平','刚','桂英','建华','秀兰','志强','丽','鑫')
       ),
       CASE WHEN MOD(n,100)<49 THEN 'M' WHEN MOD(n,100)<98 THEN 'F' ELSE 'U' END,
       DATE_SUB(DATE(event_time),INTERVAL (18+MOD(CRC32(CONCAT('age',n)),73)) YEAR),
       18+MOD(CRC32(CONCAT('age',n)),73),
       CONCAT('SIM4201',DATE_FORMAT(DATE_SUB(DATE(event_time),INTERVAL (18+MOD(CRC32(CONCAT('age',n)),73)) YEAR),'%Y%m%d'),LPAD(n,8,'0')),
       CONCAT('1',ELT(MOD(n,5)+1,'38','39','86','58','88'),LPAD(MOD(CRC32(CONCAT('phone',n)),1000000000),9,'0')),
       'PASSED'
FROM tmp_whfh_seq
ON DUPLICATE KEY UPDATE patient_no=VALUES(patient_no),name=VALUES(name),gender=VALUES(gender),birthday=VALUES(birthday),
  age=VALUES(age),id_card=VALUES(id_card),phone=VALUES(phone),quality_status='PASSED';

INSERT INTO visit(source_system,source_id,patient_id,visit_type,visit_no,department_code,department_name,doctor_code,doctor_name,admission_time,discharge_time)
SELECT @whfh_source_system,CONCAT('WHFH-V-',LPAD(s.n,8,'0')),p.id,
       CASE WHEN MOD(s.n,100)<72 THEN 'OUTPATIENT' WHEN MOD(s.n,100)<94 THEN 'INPATIENT' WHEN MOD(s.n,100)<98 THEN 'PHYSICAL' ELSE 'OTHER' END,
       CONCAT('V',DATE_FORMAT(s.event_time,'%Y%m%d'),LPAD(MOD(s.n-1,@whfh_daily_volume)+1,3,'0')),
       CONCAT('D',LPAD(MOD(s.n,12)+1,3,'0')),
       ELT(MOD(CRC32(CONCAT('dept',s.n)),12)+1,'病理科','消化内科','呼吸内科','心血管内科','神经内科','肿瘤科','普外科','胸外科','泌尿外科','妇科','骨科','急诊科'),
       CONCAT('DR',LPAD(MOD(s.n,80)+1,3,'0')),
       CONCAT(ELT(MOD(s.n,10)+1,'李','王','张','陈','刘','周','杨','赵','黄','吴'),'医生'),
       s.event_time,
       CASE WHEN MOD(s.n,100)>=72 AND MOD(s.n,100)<94 THEN DATE_ADD(s.event_time,INTERVAL (1+MOD(s.n,10)) DAY) ELSE NULL END
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_type=VALUES(visit_type),visit_no=VALUES(visit_no),
  department_code=VALUES(department_code),department_name=VALUES(department_name),doctor_code=VALUES(doctor_code),doctor_name=VALUES(doctor_name),
  admission_time=VALUES(admission_time),discharge_time=VALUES(discharge_time);

INSERT INTO diagnosis(source_system,source_id,patient_id,visit_id,diagnosis_code,diagnosis_name,diagnosis_type,diagnosis_time)
SELECT @whfh_source_system,CONCAT('WHFH-D-',LPAD(s.n,8,'0')),p.id,v.id,
       ELT(MOD(CRC32(CONCAT('diag',s.n)),12)+1,'K29.7','K63.5','J18.9','I10','E11.9','I25.1','C18.9','D12.6','N40','N20.0','M17.9','R91.1'),
       ELT(MOD(CRC32(CONCAT('diag',s.n)),12)+1,'胃炎','结肠息肉','肺炎','高血压','2型糖尿病','冠心病','结肠恶性肿瘤','结肠良性肿瘤','前列腺增生','肾结石','膝关节病','肺结节'),
       'PRIMARY',DATE_ADD(s.event_time,INTERVAL 20 MINUTE)
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),diagnosis_code=VALUES(diagnosis_code),
  diagnosis_name=VALUES(diagnosis_name),diagnosis_type=VALUES(diagnosis_type),diagnosis_time=VALUES(diagnosis_time);

INSERT INTO lab_result(source_system,source_id,patient_id,visit_id,item_code,item_name,result_value,result_unit,reference_range,abnormal_flag,result_time)
SELECT @whfh_source_system,CONCAT('WHFH-L-',LPAD(s.n,8,'0')),p.id,v.id,
       ELT(MOD(s.n,8)+1,'WBC','RBC','HGB','PLT','ALT','AST','GLU','CRP'),
       ELT(MOD(s.n,8)+1,'白细胞计数','红细胞计数','血红蛋白','血小板计数','丙氨酸氨基转移酶','天门冬氨酸氨基转移酶','葡萄糖','C反应蛋白'),
       CAST(ROUND(3+MOD(CRC32(CONCAT('lab',s.n)),1500)/100,2) AS CHAR),
       ELT(MOD(s.n,8)+1,'10^9/L','10^12/L','g/L','10^9/L','U/L','U/L','mmol/L','mg/L'),
       ELT(MOD(s.n,8)+1,'3.5-9.5','3.8-5.8','115-175','125-350','9-50','15-40','3.9-6.1','0-10'),
       CASE WHEN MOD(s.n,10)<8 THEN 'N' WHEN MOD(s.n,2)=0 THEN 'H' ELSE 'L' END,
       DATE_ADD(s.event_time,INTERVAL 90 MINUTE)
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),item_code=VALUES(item_code),item_name=VALUES(item_name),
  result_value=VALUES(result_value),result_unit=VALUES(result_unit),reference_range=VALUES(reference_range),abnormal_flag=VALUES(abnormal_flag),result_time=VALUES(result_time);

INSERT INTO exam_result(source_system,source_id,patient_id,visit_id,exam_code,exam_name,exam_part,exam_result,exam_conclusion,exam_time)
SELECT @whfh_source_system,CONCAT('WHFH-E-',LPAD(s.n,8,'0')),p.id,v.id,
       ELT(MOD(s.n,6)+1,'CT-CHEST','CT-ABD','US-ABD','MR-HEAD','XR-CHEST','ECG'),
       ELT(MOD(s.n,6)+1,'胸部CT','腹部CT','腹部超声','头颅MRI','胸部X线','心电图'),
       ELT(MOD(s.n,6)+1,'胸部','腹部','腹部','头颅','胸部','心脏'),
       ELT(MOD(s.n,5)+1,'未见明显异常','轻度炎性改变','局部结节影','建议结合临床随访','慢性退行性改变'),
       ELT(MOD(s.n,5)+1,'未见明显异常','考虑良性改变','建议复查','临床结合','随访观察'),
       DATE_ADD(s.event_time,INTERVAL 2 HOUR)
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,10)<6
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),exam_code=VALUES(exam_code),exam_name=VALUES(exam_name),
  exam_part=VALUES(exam_part),exam_result=VALUES(exam_result),exam_conclusion=VALUES(exam_conclusion),exam_time=VALUES(exam_time);

INSERT INTO medical_operation(source_system,source_id,patient_id,visit_id,operation_code,operation_name,operation_time,operator_name)
SELECT @whfh_source_system,CONCAT('WHFH-O-',LPAD(s.n,8,'0')),p.id,v.id,
       ELT(MOD(s.n,5)+1,'45.23','45.42','33.24','55.03','68.29'),
       ELT(MOD(s.n,5)+1,'结肠镜下息肉切除术','结肠部分切除术','胸腔镜肺楔形切除术','经皮肾镜取石术','宫腔镜检查术'),
       DATE_ADD(s.event_time,INTERVAL 1 DAY),CONCAT(ELT(MOD(s.n,8)+1,'李','王','张','陈','刘','周','杨','赵'),'主任')
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,20)=0
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),operation_code=VALUES(operation_code),
  operation_name=VALUES(operation_name),operation_time=VALUES(operation_time),operator_name=VALUES(operator_name);

INSERT INTO medication(source_system,source_id,patient_id,visit_id,drug_code,drug_name,dosage,unit,frequency,route,start_time,end_time)
SELECT @whfh_source_system,CONCAT('WHFH-M-',LPAD(s.n,8,'0')),p.id,v.id,
       ELT(MOD(s.n,8)+1,'AMOX','OMEP','METF','AMLO','ATOR','LEVO','ASP','CEF'),
       ELT(MOD(s.n,8)+1,'阿莫西林','奥美拉唑','二甲双胍','氨氯地平','阿托伐他汀','左氧氟沙星','阿司匹林','头孢呋辛'),
       ELT(MOD(s.n,4)+1,'0.5','10','20','100'),'mg',ELT(MOD(s.n,3)+1,'QD','BID','TID'),
       ELT(MOD(s.n,3)+1,'PO','IV','IM'),s.event_time,DATE_ADD(s.event_time,INTERVAL (3+MOD(s.n,12)) DAY)
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,10)<7
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),drug_code=VALUES(drug_code),drug_name=VALUES(drug_name),
  dosage=VALUES(dosage),unit=VALUES(unit),frequency=VALUES(frequency),route=VALUES(route),start_time=VALUES(start_time),end_time=VALUES(end_time);

-- 生成病理病例与“仅记录”数字切片；不创建 MinIO 对象，不伪装 READY。
INSERT INTO pathology_case(patient_id,visit_id,pathology_no,specimen_name,specimen_type_code,clinical_diagnosis,pathology_diagnosis,case_status)
SELECT p.id,v.id,CONCAT('WHFH-',DATE_FORMAT(s.event_time,'%Y%m%d'),'-',LPAD(MOD(s.n-1,@whfh_daily_volume)+1,3,'0')),
       ELT(MOD(s.n,8)+1,'胃黏膜组织','结肠组织','肺组织','乳腺组织','甲状腺组织','前列腺组织','宫颈组织','皮肤组织'),
       'TISSUE',d.diagnosis_name,
       ELT(MOD(s.n,6)+1,'慢性炎症性改变','良性增生性病变','腺瘤性病变','不典型增生','恶性肿瘤待免疫组化确认','未见明确恶性证据'),
       'CREATED'
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN diagnosis d ON d.source_system=@whfh_source_system AND d.source_id=CONCAT('WHFH-D-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_id=VALUES(patient_id),visit_id=VALUES(visit_id),specimen_name=VALUES(specimen_name),
  specimen_type_code=VALUES(specimen_type_code),clinical_diagnosis=VALUES(clinical_diagnosis),pathology_diagnosis=VALUES(pathology_diagnosis),case_status=VALUES(case_status);

SET @whfh_hot_id = (SELECT id FROM storage_target WHERE storage_class='HOT' AND enabled=1 ORDER BY id LIMIT 1);
SET @whfh_hot_bucket = COALESCE((SELECT bucket FROM storage_target WHERE id=@whfh_hot_id),'pathology-original');

DELETE FROM slide_file WHERE object_key LIKE 'metadata-only/wuhan-first-hospital/2026/%';

INSERT INTO slide_file(case_id,slide_no,file_name,display_name,specimen_type_code,file_extension,file_format,file_size,
  bucket_name,object_key,adapter_type,sdk_status,width,height,level_count,levels_json,md5,storage_target_id,storage_class,
  archive_status,status,error_message,scan_time,deleted)
SELECT c.id,
       CONCAT('WHFH-S-',LPAD(s.n,8,'0')),
       CONCAT('WHFH-S-',LPAD(s.n,8,'0'),'.',LOWER(ELT(MOD(s.n,11)+1,'SVS','KFB','TMAP','MDSX','DMETRIX','FENLAN','ZYP','SDPC','HWP','TRON','CSP'))),
       CONCAT('武汉市第一医院模拟切片-',DATE_FORMAT(s.event_time,'%Y%m%d'),'-',LPAD(MOD(s.n-1,@whfh_daily_volume)+1,3,'0')),
       'TISSUE',LOWER(ELT(MOD(s.n,11)+1,'SVS','KFB','TMAP','MDSX','DMETRIX','FENLAN','ZYP','SDPC','HWP','TRON','CSP')),
       ELT(MOD(s.n,11)+1,'SVS','KFB','TMAP','MDSX','DMETRIX','FENLAN','ZYP','SDPC','HWP','TRON','CSP'),
       0,@whfh_hot_bucket,CONCAT('metadata-only/wuhan-first-hospital/2026/',LPAD(s.n,8,'0')),
       NULL,'NOT_APPLICABLE',NULL,NULL,NULL,NULL,NULL,@whfh_hot_id,'HOT','NOT_ARCHIVED','METADATA_ONLY',
       '仅登记数字切片信息，未提供原始文件；无法阅片、下载、解析、归档或打包上报。',s.event_time,0
FROM tmp_whfh_seq s
JOIN pathology_case c ON c.pathology_no=CONCAT('WHFH-',DATE_FORMAT(s.event_time,'%Y%m%d'),'-',LPAD(MOD(s.n-1,@whfh_daily_volume)+1,3,'0'));

-- 同步源库：用于“数据源 → 采集任务 → 映射 → 质控”演示。
INSERT INTO mock_hospital.whfh_his_patient(id,patient_no,patient_name,sex_code,birthday,age,id_card,phone,update_time)
SELECT p.source_id,p.patient_no,p.name,CASE p.gender WHEN 'M' THEN '1' WHEN 'F' THEN '2' ELSE '9' END,
       p.birthday,p.age,p.id_card,p.phone,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_no=VALUES(patient_no),patient_name=VALUES(patient_name),sex_code=VALUES(sex_code),birthday=VALUES(birthday),
  age=VALUES(age),id_card=VALUES(id_card),phone=VALUES(phone),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_his_visit(id,patient_source_id,visit_no,visit_type,department_code,department_name,doctor_code,doctor_name,admission_time,discharge_time,update_time)
SELECT v.source_id,p.source_id,v.visit_no,v.visit_type,v.department_code,v.department_name,v.doctor_code,v.doctor_name,v.admission_time,v.discharge_time,s.event_time
FROM tmp_whfh_seq s
JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_no=VALUES(visit_no),visit_type=VALUES(visit_type),department_code=VALUES(department_code),
  department_name=VALUES(department_name),doctor_code=VALUES(doctor_code),doctor_name=VALUES(doctor_name),admission_time=VALUES(admission_time),
  discharge_time=VALUES(discharge_time),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_emr_diagnosis(id,patient_source_id,visit_source_id,diagnosis_code,diagnosis_name,diagnosis_type,diagnosis_time,update_time)
SELECT d.source_id,p.source_id,v.source_id,d.diagnosis_code,d.diagnosis_name,d.diagnosis_type,d.diagnosis_time,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN diagnosis d ON d.source_system=@whfh_source_system AND d.source_id=CONCAT('WHFH-D-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_source_id=VALUES(visit_source_id),diagnosis_code=VALUES(diagnosis_code),
  diagnosis_name=VALUES(diagnosis_name),diagnosis_type=VALUES(diagnosis_type),diagnosis_time=VALUES(diagnosis_time),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_lis_result(id,patient_source_id,visit_source_id,item_code,item_name,result_value,result_unit,reference_range,abnormal_flag,result_time,update_time)
SELECT l.source_id,p.source_id,v.source_id,l.item_code,l.item_name,l.result_value,l.result_unit,l.reference_range,l.abnormal_flag,l.result_time,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN lab_result l ON l.source_system=@whfh_source_system AND l.source_id=CONCAT('WHFH-L-',LPAD(s.n,8,'0'))
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_source_id=VALUES(visit_source_id),item_code=VALUES(item_code),item_name=VALUES(item_name),
  result_value=VALUES(result_value),result_unit=VALUES(result_unit),reference_range=VALUES(reference_range),abnormal_flag=VALUES(abnormal_flag),result_time=VALUES(result_time),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_pacs_exam(id,patient_source_id,visit_source_id,exam_code,exam_name,exam_part,exam_result,exam_conclusion,exam_time,update_time)
SELECT e.source_id,p.source_id,v.source_id,e.exam_code,e.exam_name,e.exam_part,e.exam_result,e.exam_conclusion,e.exam_time,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN exam_result e ON e.source_system=@whfh_source_system AND e.source_id=CONCAT('WHFH-E-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,10)<6
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_source_id=VALUES(visit_source_id),exam_code=VALUES(exam_code),exam_name=VALUES(exam_name),
  exam_part=VALUES(exam_part),exam_result=VALUES(exam_result),exam_conclusion=VALUES(exam_conclusion),exam_time=VALUES(exam_time),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_his_operation(id,patient_source_id,visit_source_id,operation_code,operation_name,operation_time,operator_name,update_time)
SELECT o.source_id,p.source_id,v.source_id,o.operation_code,o.operation_name,o.operation_time,o.operator_name,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN medical_operation o ON o.source_system=@whfh_source_system AND o.source_id=CONCAT('WHFH-O-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,20)=0
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_source_id=VALUES(visit_source_id),operation_code=VALUES(operation_code),
  operation_name=VALUES(operation_name),operation_time=VALUES(operation_time),operator_name=VALUES(operator_name),update_time=VALUES(update_time);

INSERT INTO mock_hospital.whfh_his_medication(id,patient_source_id,visit_source_id,drug_code,drug_name,dosage,unit,frequency,route,start_time,end_time,update_time)
SELECT m.source_id,p.source_id,v.source_id,m.drug_code,m.drug_name,m.dosage,m.unit,m.frequency,m.route,m.start_time,m.end_time,s.event_time
FROM tmp_whfh_seq s JOIN patient p ON p.source_system=@whfh_source_system AND p.source_id=CONCAT('WHFH-P-',LPAD(s.n,8,'0'))
JOIN visit v ON v.source_system=@whfh_source_system AND v.source_id=CONCAT('WHFH-V-',LPAD(s.n,8,'0'))
JOIN medication m ON m.source_system=@whfh_source_system AND m.source_id=CONCAT('WHFH-M-',LPAD(s.n,8,'0'))
WHERE MOD(s.n,10)<7
ON DUPLICATE KEY UPDATE patient_source_id=VALUES(patient_source_id),visit_source_id=VALUES(visit_source_id),drug_code=VALUES(drug_code),drug_name=VALUES(drug_name),
  dosage=VALUES(dosage),unit=VALUES(unit),frequency=VALUES(frequency),route=VALUES(route),start_time=VALUES(start_time),end_time=VALUES(end_time),update_time=VALUES(update_time);

-- 复制现有本地 Mock 数据源的加密凭据，避免在 SQL 中保存明文密码。
INSERT INTO data_source_config(name,code,connector_type,system_type,database_type,host,port,database_name,username,password_encrypted,jdbc_url,enabled)
SELECT '武汉市第一医院模拟数据源','WHFH_DEMO','DATABASE',@whfh_source_system,'MYSQL',host,port,'mock_hospital',username,password_encrypted,jdbc_url,1
FROM data_source_config WHERE code='MOCK_HIS'
ON DUPLICATE KEY UPDATE name=VALUES(name),connector_type='DATABASE',system_type=@whfh_source_system,database_type='MYSQL',
  host=VALUES(host),port=VALUES(port),database_name='mock_hospital',username=VALUES(username),password_encrypted=VALUES(password_encrypted),jdbc_url=VALUES(jdbc_url),enabled=1;

SET @whfh_source_id = (SELECT id FROM data_source_config WHERE code='WHFH_DEMO' LIMIT 1);

INSERT INTO mapping_template(name,business_type,source_system,enabled)
SELECT '武汉市第一医院患者映射','PATIENT',@whfh_source_system,1
WHERE NOT EXISTS(SELECT 1 FROM mapping_template WHERE business_type='PATIENT' AND source_system=@whfh_source_system);
SET @whfh_patient_template = (SELECT id FROM mapping_template WHERE business_type='PATIENT' AND source_system=@whfh_source_system ORDER BY id LIMIT 1);
DELETE FROM mapping_field WHERE template_id=@whfh_patient_template;
INSERT INTO mapping_field(template_id,source_field,target_field,rule_type,rule_config,sort_order) VALUES
(@whfh_patient_template,'id','source_id','DIRECT',NULL,1),
(@whfh_patient_template,'patient_no','patient_no','TRIM',NULL,2),
(@whfh_patient_template,'patient_name','name','TRIM',NULL,3),
(@whfh_patient_template,'sex_code','gender','DICTIONARY','SEX',4),
(@whfh_patient_template,'birthday','birthday','DATE_FORMAT','yyyy-MM-dd',5),
(@whfh_patient_template,'age','age','NUMBER',NULL,6),
(@whfh_patient_template,'id_card','id_card','TRIM',NULL,7),
(@whfh_patient_template,'phone','phone','TRIM',NULL,8);

-- 创建 7 个手动演示任务。水位固定在前一天末尾，首次点击“立即执行”各处理最后一天最多 300 条。
INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-患者采集',@whfh_source_id,'PATIENT','1h',
 'SELECT id,patient_no,patient_name,sex_code,birthday,age,id_card,phone,update_time FROM whfh_his_patient WHERE update_time > :lastSyncTime ORDER BY update_time,id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-患者采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-就诊采集',@whfh_source_id,'VISIT','1h',
 'SELECT v.id AS source_id,p.id AS patient_id,v.visit_type,v.visit_no,v.department_code,v.department_name,v.doctor_code,v.doctor_name,v.admission_time,v.discharge_time,v.update_time FROM whfh_his_visit v JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=v.patient_source_id WHERE v.update_time > :lastSyncTime ORDER BY v.update_time,v.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-就诊采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-诊断采集',@whfh_source_id,'DIAGNOSIS','1h',
 'SELECT d.id AS source_id,p.id AS patient_id,v.id AS visit_id,d.diagnosis_code,d.diagnosis_name,d.diagnosis_type,d.diagnosis_time,d.update_time FROM whfh_emr_diagnosis d JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=d.patient_source_id JOIN medical_report.visit v ON v.source_system=''WHFH_HIS'' AND v.source_id=d.visit_source_id WHERE d.update_time > :lastSyncTime ORDER BY d.update_time,d.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-诊断采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-检验采集',@whfh_source_id,'LAB','1h',
 'SELECT l.id AS source_id,p.id AS patient_id,v.id AS visit_id,l.item_code,l.item_name,l.result_value,l.result_unit,l.reference_range,l.abnormal_flag,l.result_time,l.update_time FROM whfh_lis_result l JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=l.patient_source_id JOIN medical_report.visit v ON v.source_system=''WHFH_HIS'' AND v.source_id=l.visit_source_id WHERE l.update_time > :lastSyncTime ORDER BY l.update_time,l.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-检验采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-检查采集',@whfh_source_id,'EXAM','1h',
 'SELECT e.id AS source_id,p.id AS patient_id,v.id AS visit_id,e.exam_code,e.exam_name,e.exam_part,e.exam_result,e.exam_conclusion,e.exam_time,e.update_time FROM whfh_pacs_exam e JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=e.patient_source_id JOIN medical_report.visit v ON v.source_system=''WHFH_HIS'' AND v.source_id=e.visit_source_id WHERE e.update_time > :lastSyncTime ORDER BY e.update_time,e.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-检查采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-手术采集',@whfh_source_id,'OPERATION','1h',
 'SELECT o.id AS source_id,p.id AS patient_id,v.id AS visit_id,o.operation_code,o.operation_name,o.operation_time,o.operator_name,o.update_time FROM whfh_his_operation o JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=o.patient_source_id JOIN medical_report.visit v ON v.source_system=''WHFH_HIS'' AND v.source_id=o.visit_source_id WHERE o.update_time > :lastSyncTime ORDER BY o.update_time,o.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-手术采集');

INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
SELECT '武汉市第一医院-用药采集',@whfh_source_id,'MEDICATION','1h',
 'SELECT m.id AS source_id,p.id AS patient_id,v.id AS visit_id,m.drug_code,m.drug_name,m.dosage,m.unit,m.frequency,m.route,m.start_time,m.end_time,m.update_time FROM whfh_his_medication m JOIN medical_report.patient p ON p.source_system=''WHFH_HIS'' AND p.source_id=m.patient_source_id JOIN medical_report.visit v ON v.source_system=''WHFH_HIS'' AND v.source_id=m.visit_source_id WHERE m.update_time > :lastSyncTime ORDER BY m.update_time,m.id',
 'update_time',DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),0,NULL
WHERE @whfh_source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM collect_task WHERE task_name='武汉市第一医院-用药采集');

UPDATE collect_task
SET data_source_id=@whfh_source_id,last_sync_time=DATE_SUB(@whfh_end_date,INTERVAL 1 SECOND),enabled=0,next_run_time=NULL
WHERE task_name LIKE '武汉市第一医院-%采集' AND @whfh_source_id IS NOT NULL;

SELECT 'patient' AS item,COUNT(*) AS rows_created FROM patient WHERE source_system=@whfh_source_system
UNION ALL SELECT 'visit',COUNT(*) FROM visit WHERE source_system=@whfh_source_system
UNION ALL SELECT 'diagnosis',COUNT(*) FROM diagnosis WHERE source_system=@whfh_source_system
UNION ALL SELECT 'lab_result',COUNT(*) FROM lab_result WHERE source_system=@whfh_source_system
UNION ALL SELECT 'exam_result',COUNT(*) FROM exam_result WHERE source_system=@whfh_source_system
UNION ALL SELECT 'medical_operation',COUNT(*) FROM medical_operation WHERE source_system=@whfh_source_system
UNION ALL SELECT 'medication',COUNT(*) FROM medication WHERE source_system=@whfh_source_system
UNION ALL SELECT 'pathology_case',COUNT(*) FROM pathology_case WHERE pathology_no LIKE 'WHFH-%'
UNION ALL SELECT 'slide_file_metadata_only',COUNT(*) FROM slide_file WHERE object_key LIKE 'metadata-only/wuhan-first-hospital/2026/%';

DROP TEMPORARY TABLE IF EXISTS tmp_whfh_seq;
