import {
  getMeta,
  getCityDetail,
  listCitiesSummary,
  listCityIds,
  listCityManifest
} from '../sample-data.js';

export function createMockProvider() {
  return {
    sourceId: 'mock',
    async health() {
      return { ok: true, source: 'MOCK' };
    },
    async getMeta() {
      return getMeta();
    },
    async listCityIds() {
      return listCityIds();
    },
    async listCityManifest() {
      return listCityManifest();
    },
    async listCitiesSummary() {
      return listCitiesSummary();
    },
    async getCitySummary(cityId) {
      return listCitiesSummary().find((city) => city.id === cityId) || null;
    },
    async getCityDetail(cityId) {
      return getCityDetail(cityId);
    }
  };
}
