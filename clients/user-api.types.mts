/** Compile-only FE contract checks; this file does not execute requests. */
import { createUserApi, type DecimalString } from './user-api.mjs';
const api = createUserApi({ baseUrl: 'http://localhost:8093' });
async function financialScreen() {
  const profile = await api.security('FPT', { exchange: 'HOSE' });
  const company = await api.company(profile.data.companyId);
  const reports = await api.statements('FPT', { exchange: 'HOSE' });
  const detail = await api.statement('FPT', reports.items[0].id, { exchange: 'HOSE' });
  const matrix = await api.financials('FPT', { codes: [detail.data.items[0].code], type: detail.data.statementType, period: detail.data.periodType });
  const values: (DecimalString | null)[] = Object.values(matrix.data.series[0].values).map(point => point.value);
  const local = await api.securityLocalAi('FPT', { exchange: 'HOSE' });
  const approved: false = local.data.publicResultsAvailable;
  return { company, values, approved };
}
void financialScreen;
// @ts-expect-error Old SQL Server numeric company IDs are not this API's UUID strings.
void api.company(12);
// @ts-expect-error Financial item selection is required, never an implicit invented set of codes.
void api.financials('FPT', {});
// @ts-expect-error The public client has no training/admin execution operation.
void api.execute({ modelId: 'npat_h2' });
