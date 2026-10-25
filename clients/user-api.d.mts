export type DecimalString = string;
export type UUID = string;
export type Projection = Record<string, unknown>;
export type Query = Record<string, string | number | boolean | readonly string[] | undefined | null>;
export interface RequestOptions { signal?: AbortSignal }
export interface Meta { timezone: 'Asia/Ho_Chi_Minh'; numberEncoding: 'DECIMAL_STRING'; generatedAt: string; notes: string[] }
export interface Data<T> { data: T; meta: Meta }
export interface Page<T> { items: T[]; total: number; page: number; size: number; hasNext: boolean; meta: Meta }
export interface Security extends Projection {
  id: UUID; companyId: UUID; symbol: string; exchange: string; companyName: string;
  currency: string | null; sharesOutstanding: DecimalString | null; active: boolean;
}
export interface Company extends Projection { id: UUID; legalName: string; shortName: string | null }
export interface Period extends Projection { id: UUID; fiscalYear: number; periodType: 'FY' | 'Q1' | 'Q2' | 'Q3' | 'Q4'; periodStart: string; periodEnd: string }
export type StatementType = 'BALANCE_SHEET' | 'INCOME_STATEMENT' | 'CASH_FLOW';
export interface StatementItem extends Projection { code: string; name: string | null; value: DecimalString | null; unit: string | null }
export interface Statement extends Period { statementType: StatementType; reportScope: string | null; periodBasis: string | null; publishedAt: string | null; sourceCode: string; items?: StatementItem[] }
export interface MatrixPoint { value: DecimalString | null; statementId: UUID; sourceCode: string; publishedAt: string | null }
export interface MatrixSeries { code: string; name: string | null; unit: string | null; scope: string | null; basis: string | null; currency: string | null; unitScale: DecimalString | null; values: Record<UUID, MatrixPoint> }
export interface FinancialMatrix { security: Security; statementType: string; periods: Period[]; series: MatrixSeries[]; hasEarlierPeriods: boolean; missingCodes: string[] }
export interface FinancialQuery extends Query { codes: string | readonly string[]; type?: 'BALANCE_SHEET' | 'INCOME_STATEMENT' | 'CASH_FLOW'; period?: 'QUARTER' | 'FY' | 'Q1' | 'Q2' | 'Q3' | 'Q4'; scope?: 'CONSOLIDATED' | 'SEPARATE' | 'UNKNOWN'; periodLimit?: number }
export interface CompareQuery extends Query { symbols: string | readonly string[]; codes: string | readonly string[]; origin?: 'PROVIDER' | 'DERIVED' }
export interface MetricObservation extends Projection { code: string; value: DecimalString | null; valueText: string | null; asOfDate: string; unit: string | null; derived: boolean }
export interface DataStatus extends Projection { security: Security; availability: 'PARTIAL' | 'AVAILABLE_WITH_UNVERIFIED_HISTORY'; pointInTimeCertified: false; limitations: string[] }
export interface Article extends Projection { id: UUID; title: string; url: string | null; hasContent: boolean; publishedAt: string | null; crawledAt: string; contentText?: string | null }
export interface ResearchPackage { id: UUID; status: 'RESEARCH_ONLY'; registeredAt: string }
export interface LocalAiModel {
  packageId: UUID; registeredAt: string; modelId: string; kind: string; horizonQuarters: number | null; targetPeriod: string | null;
  selectedAlgorithm: string | null; developmentGatePassed: boolean | null; registeredInputs: DecimalString;
  firstInputAsOf: string | null; lastInputAsOf: string | null; storedSelectedResearchResults: DecimalString;
  readiness: 'RESEARCH_ONLY'; publicResultsAvailable: false; inputMode: 'REGISTERED_SNAPSHOT'; featureSubmissionAllowed: false;
  outputContract: { type: string; fields: string[]; unit?: string; note?: string; reportScope?: string; periodBasis?: string };
}
export interface LocalAiSecurity {
  security: Security; state: 'NOT_INSTALLED' | 'NO_REGISTERED_PACKAGE' | 'TICKER_IDENTITY_AMBIGUOUS' | 'NO_REGISTERED_INPUT' | 'RESEARCH_ONLY';
  package?: ResearchPackage | null; models: LocalAiModel[]; modelsTruncated?: boolean; publicResultsAvailable: false; executionAvailable: false;
}
export interface AnalysisAvailability extends Projection {
  executesModels: false;
  localAi: Projection & { publicPredictionReader: 'NOT_INTEGRATED'; reason: 'PUBLICATION_POLICY_REQUIRED'; registeredSuite: Data<Projection> };
}
export interface Overview extends Projection {
  profile: Data<Security>; dataStatus: Data<DataStatus>; statements: Page<Statement>; metrics: Page<MetricObservation>;
  news: Page<Article>; financialScenarios: Data<Projection[]>; financialScenarioState: 'AVAILABLE' | 'NO_ELIGIBLE_RESULT';
  localAi: Data<LocalAiSecurity>; analysisAvailability: Data<AnalysisAvailability>;
}
type ListCall<T> = (query?: Query, options?: RequestOptions) => Promise<Page<T>>;
type DetailCall<T> = (key: string, query?: Query, options?: RequestOptions) => Promise<Data<T>>;
type SecurityList<T> = (symbol: string, query?: Query, options?: RequestOptions) => Promise<Page<T>>;
export interface UserApi {
  securities: ListCall<Security>; security: DetailCall<Security>; companies: ListCall<Company>; company: DetailCall<Company & { securities: Security[] }>;
  marketOverview(query?: Query, options?: RequestOptions): Promise<Data<Projection>>;
  quote: DetailCall<Projection>; prices: SecurityList<Projection>; indices: ListCall<Projection>;
  indexPrices: SecurityList<Projection>; indexMembers: SecurityList<Projection>; macroSeries: ListCall<Projection>; macroObservations: SecurityList<Projection>;
  statements: SecurityList<Statement>;
  statement(symbol: string, id: UUID, query?: Query, options?: RequestOptions): Promise<Data<Statement & { items: StatementItem[] }>>;
  metrics: ListCall<Projection>; securityMetrics: SecurityList<MetricObservation>; news: ListCall<Article>; article: DetailCall<Article>;
  newsAnalysis: DetailCall<Projection[]>; forecasts: DetailCall<Projection[]>;
  marketFilters(query?: Query, options?: RequestOptions): Promise<Data<Projection>>;
  dataStatus: DetailCall<DataStatus>; periods: DetailCall<Period[]>;
  lineage(symbol: string, id: UUID, query?: Query, options?: RequestOptions): Promise<Data<Projection>>;
  priceLineage(symbol: string, id: string, query?: Query, options?: RequestOptions): Promise<Data<Projection>>;
  metricLineage(symbol: string, id: UUID, query?: Query, options?: RequestOptions): Promise<Data<MetricObservation & { origin: 'PROVIDER' | 'DERIVED'; catalogFormula: string | null; pointInTimeCertified: false }>>;
  statementItems: SecurityList<Projection>;
  financials(symbol: string, query: FinancialQuery, options?: RequestOptions): Promise<Data<FinancialMatrix>>;
  screener: ListCall<Security & Projection>;
  compare(query: CompareQuery, options?: RequestOptions): Promise<Data<Projection>>;
  foreignTrades: SecurityList<Projection>; referenceObservations: SecurityList<Projection>;
  analysisAvailability(query?: Query, options?: RequestOptions): Promise<Data<AnalysisAvailability>>;
  overview: DetailCall<Overview>; localAiModels: ListCall<LocalAiModel>; securityLocalAi: DetailCall<LocalAiSecurity>;
  contract(query?: Query, options?: RequestOptions): Promise<Projection>;
}
export type ErrorKind = 'HTTP' | 'NETWORK' | 'TIMEOUT' | 'ABORTED' | 'INVALID_RESPONSE';
export class UserApiError extends Error { constructor(message: string, kind: ErrorKind, status?: number); kind: ErrorKind; status?: number }
export const userOperations: Readonly<Record<keyof UserApi, string>>;
export function createUserApi(options: { baseUrl: string; fetch?: typeof fetch; timeoutMs?: number }): Readonly<UserApi>;
