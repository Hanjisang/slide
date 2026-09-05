<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { Check, Delete, Edit, Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import api from '../api'
import { statusLabel, statusType } from '../utils/status'

const route = useRoute()
const tabFromRoute = () => route.query.tab === 'conversion' ? 'conversion' : route.query.tab === 'validation' ? 'rules' : 'errors'
const active = ref(tabFromRoute())
const loading = ref(false)
const errors = ref<any[]>([])
const rules = ref<any[]>([])
const templates = ref<any[]>([])
const fields = ref<any[]>([])
const dictionaries = ref<any[]>([])
const items = ref<any[]>([])
const correctDialog = ref(false)
const selectedError = ref<any>()
const correction = reactive({ value: '', note: '' })
const ruleDialog = ref(false)
const editingRuleId = ref<number>()
const ruleForm = reactive<any>({ businessType: 'PATIENT', fieldName: '', ruleType: 'NOT_NULL', ruleConfig: '', errorMessage: '', enabled: true })
const fieldDialog = ref(false)
const editingFieldId = ref<number>()
const fieldForm = reactive<any>({ templateId: 1, sourceField: '', targetField: '', ruleType: 'DIRECT', ruleConfig: '', sortOrder: 0 })
const dictDialog = ref(false)
const editingDictId = ref<number>()
const dictForm = reactive<any>({ dictionaryId: 1, sourceValue: '', targetValue: '', description: '', enabled: true })
const conversionBusinessType = ref('PATIENT')
const conversionSourceSystem = ref('HIS')
const conversionInput = ref('{\n  "sex_code": "1",\n  "patient_name": " 张三 "\n}')
const conversionResult = ref<any>()
const conversionRunning = ref(false)

const ruleTypes = ['NOT_NULL', 'MIN', 'MAX', 'RANGE', 'REGEX', 'ENUM', 'DATE_RANGE', 'LENGTH', 'UNIQUE', 'CROSS_FIELD', 'CROSS_RECORD']
const mappingTypes = [
  ['DIRECT', '直接映射'], ['TRIM', '去除首尾空格'], ['UPPERCASE', '转大写'], ['LOWERCASE', '转小写'],
  ['DATE_FORMAT', '日期格式转换'], ['NUMBER', '数值转换'], ['DEFAULT_VALUE', '默认值'], ['REPLACE', '文本替换'],
  ['DICTIONARY', '字典标准化'], ['CONCAT', '字段拼接'],
]
const ruleLabels: Record<string, string> = { NOT_NULL: '不能为空', MIN: '最小值', MAX: '最大值', RANGE: '范围', REGEX: '正则格式', ENUM: '枚举', DATE_RANGE: '日期范围', LENGTH: '长度', UNIQUE: '唯一性', CROSS_FIELD: '跨字段一致性', CROSS_RECORD: '跨记录一致性' }
const businessTypes = ['PATIENT', 'VISIT', 'DIAGNOSIS', 'LAB', 'EXAM', 'OPERATION', 'MEDICATION']

async function load() {
  loading.value = true
  try {
    ;[errors.value, rules.value, templates.value, fields.value, dictionaries.value, items.value] = await Promise.all([
      api.get('/validation-errors'), api.get('/governance/validation-rules'), api.get('/governance/mapping-templates'),
      api.get('/governance/mapping-fields'), api.get('/governance/dictionaries'), api.get('/governance/dictionary-items'),
    ])
  } finally { loading.value = false }
}
function openCorrect(row: any) { selectedError.value = row; correction.value = row.current_value ?? ''; correction.note = ''; correctDialog.value = true }
async function saveCorrection() { await api.put(`/validation-errors/${selectedError.value.id}/value`, correction); correctDialog.value = false; ElMessage.success('修改已保存，请重新校验'); await load() }
async function revalidate(row: any) { const result: any = await api.post(`/validation-errors/${row.id}/revalidate`); ElMessage[result.passed ? 'success' : 'warning'](result.passed ? '重新校验通过' : '仍有未通过规则'); await load() }
async function ignore(row: any) { await ElMessageBox.confirm('忽略后该异常不再阻止当前数据上报，是否继续？', '确认忽略', { type: 'warning' }); await api.post(`/validation-errors/${row.id}/ignore`); await load() }
function addRule() { editingRuleId.value = undefined; Object.assign(ruleForm, { businessType: 'PATIENT', fieldName: '', ruleType: 'NOT_NULL', ruleConfig: '', errorMessage: '', enabled: true }); ruleDialog.value = true }
function editRule(row: any) { editingRuleId.value = row.id; Object.assign(ruleForm, { businessType: row.business_type, fieldName: row.field_name, ruleType: row.rule_type, ruleConfig: row.rule_config || '', errorMessage: row.error_message, enabled: !!row.enabled }); ruleDialog.value = true }
async function saveRule() { const url = `/governance/validation-rules${editingRuleId.value ? `/${editingRuleId.value}` : ''}`; if (editingRuleId.value) await api.put(url, ruleForm); else await api.post(url, ruleForm); ruleDialog.value = false; ElMessage.success('校验规则已保存'); await load() }
async function removeRule(row: any) { await ElMessageBox.confirm('删除后该规则不再参与校验，是否继续？', '删除校验规则', { type: 'warning' }); await api.delete(`/governance/validation-rules/${row.id}`); await load() }
function addField() { editingFieldId.value = undefined; Object.assign(fieldForm, { templateId: templates.value[0]?.id, sourceField: '', targetField: '', ruleType: 'DIRECT', ruleConfig: '', sortOrder: fields.value.length + 1 }); fieldDialog.value = true }
function editField(row: any) { editingFieldId.value = row.id; Object.assign(fieldForm, { templateId: row.template_id, sourceField: row.source_field, targetField: row.target_field, ruleType: row.rule_type, ruleConfig: row.rule_config || '', sortOrder: row.sort_order }); fieldDialog.value = true }
async function saveField() { const url = `/governance/mapping-fields${editingFieldId.value ? `/${editingFieldId.value}` : ''}`; if (editingFieldId.value) await api.put(url, fieldForm); else await api.post(url, fieldForm); fieldDialog.value = false; ElMessage.success('清洗转换规则已保存'); await load() }
async function removeField(row: any) { await ElMessageBox.confirm('删除后该字段不再转换，是否继续？', '删除转换规则', { type: 'warning' }); await api.delete(`/governance/mapping-fields/${row.id}`); await load() }
function addDictItem() { editingDictId.value = undefined; Object.assign(dictForm, { dictionaryId: dictionaries.value[0]?.id, sourceValue: '', targetValue: '', description: '', enabled: true }); dictDialog.value = true }
function editDictItem(row: any) { editingDictId.value = row.id; Object.assign(dictForm, { dictionaryId: row.dictionary_id, sourceValue: row.source_value, targetValue: row.target_value, description: row.description || '', enabled: !!row.enabled }); dictDialog.value = true }
async function saveDictItem() { const url = `/governance/dictionary-items${editingDictId.value ? `/${editingDictId.value}` : ''}`; if (editingDictId.value) await api.put(url, dictForm); else await api.post(url, dictForm); dictDialog.value = false; ElMessage.success('标准化字典已保存'); await load() }
async function removeDictItem(row: any) { await ElMessageBox.confirm('删除后该字典值不再参与标准化，是否继续？', '删除字典项', { type: 'warning' }); await api.delete(`/governance/dictionary-items/${row.id}`); await load() }
async function runConversion() {
  let data: Record<string, unknown>
  try { data = JSON.parse(conversionInput.value) } catch { ElMessage.error('测试数据不是有效 JSON'); return }
  conversionRunning.value = true
  try { conversionResult.value = await api.post('/governance/mapping-test', { businessType: conversionBusinessType.value, sourceSystem: conversionSourceSystem.value, data }); ElMessage.success('已使用真实清洗转换引擎执行') } finally { conversionRunning.value = false }
}
function reset() { conversionInput.value = '{\n  "sex_code": "1",\n  "patient_name": " 张三 "\n}'; conversionResult.value = undefined }
watch(() => route.query.tab, () => { active.value = tabFromRoute() })
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-toolbar"><div><h2>数据清洗转换</h2><p>对采集数据进行去重、纠错、格式转换和标准化处理，使其符合上报标准和要求。</p></div><div class="toolbar-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="active==='rules'" type="primary" :icon="Plus" @click="addRule">新增校验规则</el-button><el-button v-if="active==='mapping'" type="primary" :icon="Plus" @click="addField">新增清洗规则</el-button><el-button v-if="active==='dictionary'" type="primary" :icon="Plus" @click="addDictItem">新增标准字典</el-button></div></div>
    <el-tabs v-model="active" class="tabs-panel">
      <el-tab-pane label="异常数据" name="errors"><section class="data-panel"><el-table :data="errors"><el-table-column prop="patient_name" label="患者" width="110"/><el-table-column prop="business_type" label="数据对象" width="120"/><el-table-column prop="field_name" label="字段" width="120"/><el-table-column prop="current_value" label="当前值" min-width="120"/><el-table-column label="校验规则" width="150"><template #default="{row}">{{ ruleLabels[row.rule_type] || row.rule_type }}</template></el-table-column><el-table-column prop="error_message" label="问题说明" min-width="220"/><el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column label="操作" width="230" fixed="right"><template #default="{row}"><div class="table-actions"><el-button link type="primary" @click="openCorrect(row)" :disabled="!['PENDING','FAILED'].includes(row.status)">修改</el-button><el-button link type="success" :icon="Check" @click="revalidate(row)" :disabled="!['CORRECTED','FAILED','PENDING'].includes(row.status)">重新校验</el-button><el-button link @click="ignore(row)" :disabled="!['PENDING','FAILED'].includes(row.status)">忽略</el-button></div></template></el-table-column></el-table></section></el-tab-pane>
      <el-tab-pane label="校验规则" name="rules"><section class="data-panel"><el-table :data="rules"><el-table-column prop="business_type" label="数据对象" width="130"/><el-table-column prop="field_name" label="字段" width="140"/><el-table-column label="规则类型" width="170"><template #default="{row}">{{ ruleLabels[row.rule_type] || row.rule_type }}</template></el-table-column><el-table-column prop="rule_config" label="规则参数" min-width="160"/><el-table-column prop="error_message" label="问题提示" min-width="240"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="150"><template #default="{row}"><el-button link :icon="Edit" @click="editRule(row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="removeRule(row)">删除</el-button></template></el-table-column></el-table></section></el-tab-pane>
      <el-tab-pane label="清洗规则" name="mapping"><section class="data-panel"><el-alert title="清洗规则由 MappingEngine 执行；重复采集使用来源系统+来源编号更新原记录，不新增重复行。" type="info" show-icon/><el-table :data="fields" style="margin-top:12px"><el-table-column prop="template_id" label="模板" width="80"/><el-table-column prop="source_field" label="源字段" min-width="150"/><el-table-column prop="target_field" label="标准字段" min-width="150"/><el-table-column label="转换方式" width="180"><template #default="{row}">{{ mappingTypes.find(x=>x[0]===row.rule_type)?.[1] || row.rule_type }}</template></el-table-column><el-table-column prop="rule_config" label="参数" min-width="180"/><el-table-column prop="sort_order" label="顺序" width="80"/><el-table-column label="操作" width="150"><template #default="{row}"><el-button link :icon="Edit" @click="editField(row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="removeField(row)">删除</el-button></template></el-table-column></el-table></section></el-tab-pane>
      <el-tab-pane label="标准化字典" name="dictionary"><section class="data-panel"><el-table :data="items"><el-table-column prop="dictionary_id" label="字典" width="100"/><el-table-column prop="source_value" label="源值" min-width="150"/><el-table-column prop="target_value" label="标准值" min-width="150"/><el-table-column prop="description" label="说明" min-width="240"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="150"><template #default="{row}"><el-button link :icon="Edit" @click="editDictItem(row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="removeDictItem(row)">删除</el-button></template></el-table-column></el-table></section></el-tab-pane>
      <el-tab-pane label="转换测试" name="conversion"><section class="data-panel conversion-panel"><el-alert title="输入一组采集数据，页面将调用真实 MappingEngine，展示清洗后的标准数据。" type="info" show-icon/><el-form label-width="90px" class="conversion-form"><el-row :gutter="14"><el-col :span="12"><el-form-item label="源系统"><el-input v-model="conversionSourceSystem"/></el-form-item></el-col><el-col :span="12"><el-form-item label="数据对象"><el-select v-model="conversionBusinessType" style="width:100%"><el-option v-for="x in businessTypes" :key="x" :label="x" :value="x"/></el-select></el-form-item></el-col></el-row><el-form-item label="测试数据"><el-input v-model="conversionInput" type="textarea" :rows="8" class="mono"/></el-form-item><div class="toolbar-actions"><el-button type="primary" :icon="VideoPlay" :loading="conversionRunning" @click="runConversion">执行清洗</el-button><el-button @click="reset">恢复示例</el-button></div></el-form><el-divider v-if="conversionResult" content-position="left">清洗结果</el-divider><el-row v-if="conversionResult" :gutter="18"><el-col :span="12"><h3 class="result-title">输入数据</h3><pre class="json-preview">{{ JSON.stringify(conversionResult.input, null, 2) }}</pre></el-col><el-col :span="12"><h3 class="result-title">标准化结果</h3><pre class="json-preview success-preview">{{ JSON.stringify(conversionResult.output, null, 2) }}</pre></el-col></el-row></section></el-tab-pane>
    </el-tabs>
    <el-dialog v-model="correctDialog" title="人工修改异常数据" width="min(520px,92vw)"><el-descriptions :column="1" border><el-descriptions-item label="数据对象">{{selectedError?.business_type}}</el-descriptions-item><el-descriptions-item label="字段">{{selectedError?.field_name}}</el-descriptions-item><el-descriptions-item label="问题说明">{{selectedError?.error_message}}</el-descriptions-item><el-descriptions-item label="修改建议">{{selectedError?.suggestion || '请核对业务原始数据'}}</el-descriptions-item></el-descriptions><el-form label-width="80px" style="margin-top:20px"><el-form-item label="修改值"><el-input v-model="correction.value"/></el-form-item><el-form-item label="处理说明"><el-input v-model="correction.note" type="textarea" :rows="3"/></el-form-item></el-form><template #footer><el-button @click="correctDialog=false">取消</el-button><el-button type="primary" @click="saveCorrection">保存并待重新校验</el-button></template></el-dialog>
    <el-dialog v-model="ruleDialog" :title="editingRuleId?'编辑校验规则':'新增校验规则'" width="min(560px,92vw)"><el-form :model="ruleForm" label-width="95px"><el-form-item label="数据对象"><el-select v-model="ruleForm.businessType" style="width:100%"><el-option v-for="x in businessTypes" :key="x" :label="x" :value="x"/></el-select></el-form-item><el-form-item label="字段"><el-input v-model="ruleForm.fieldName"/></el-form-item><el-form-item label="规则类型"><el-select v-model="ruleForm.ruleType" style="width:100%"><el-option v-for="x in ruleTypes" :key="x" :label="ruleLabels[x]" :value="x"/></el-select></el-form-item><el-form-item label="规则参数"><el-input v-model="ruleForm.ruleConfig" placeholder="如 0,150、M,F 或字段名"/></el-form-item><el-form-item label="问题提示"><el-input v-model="ruleForm.errorMessage"/></el-form-item><el-form-item label="启用"><el-switch v-model="ruleForm.enabled"/></el-form-item></el-form><template #footer><el-button @click="ruleDialog=false">取消</el-button><el-button type="primary" @click="saveRule">保存规则</el-button></template></el-dialog>
    <el-dialog v-model="fieldDialog" :title="editingFieldId?'编辑清洗规则':'新增清洗规则'" width="min(560px,92vw)"><el-form :model="fieldForm" label-width="95px"><el-form-item label="映射模板"><el-select v-model="fieldForm.templateId" style="width:100%"><el-option v-for="x in templates" :key="x.id" :label="x.name" :value="x.id"/></el-select></el-form-item><el-row :gutter="14"><el-col :span="12"><el-form-item label="源字段"><el-input v-model="fieldForm.sourceField"/></el-form-item></el-col><el-col :span="12"><el-form-item label="标准字段"><el-input v-model="fieldForm.targetField"/></el-form-item></el-col></el-row><el-form-item label="转换方式"><el-select v-model="fieldForm.ruleType" style="width:100%"><el-option v-for="x in mappingTypes" :key="x[0]" :label="`${x[1]}（${x[0]}）`" :value="x[0]"/></el-select></el-form-item><el-form-item label="规则参数"><el-input v-model="fieldForm.ruleConfig" placeholder="日期格式、字典类型或替换内容"/></el-form-item></el-form><template #footer><el-button @click="fieldDialog=false">取消</el-button><el-button type="primary" @click="saveField">保存规则</el-button></template></el-dialog>
    <el-dialog v-model="dictDialog" :title="editingDictId?'编辑标准字典':'新增标准字典'" width="min(520px,92vw)"><el-form :model="dictForm" label-width="80px"><el-form-item label="字典"><el-select v-model="dictForm.dictionaryId" style="width:100%"><el-option v-for="x in dictionaries" :key="x.id" :label="x.name" :value="x.id"/></el-select></el-form-item><el-form-item label="源值"><el-input v-model="dictForm.sourceValue"/></el-form-item><el-form-item label="标准值"><el-input v-model="dictForm.targetValue"/></el-form-item><el-form-item label="说明"><el-input v-model="dictForm.description"/></el-form-item><el-form-item label="启用"><el-switch v-model="dictForm.enabled"/></el-form-item></el-form><template #footer><el-button @click="dictDialog=false">取消</el-button><el-button type="primary" @click="saveDictItem">保存字典</el-button></template></el-dialog>
  </div>
</template>
