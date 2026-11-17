import http from 'k6/http';
import {check} from 'k6';

export const options = {
    scenarios: {
        decisions: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RPS || 1000),
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 100,
            maxVUs: 1000
        }
    }, thresholds: {http_req_failed: ['rate<0.01']}
};
export default function () {
    const r = http.post(`${__ENV.URL || 'http://localhost:8086'}/api/v1/ads/decide`, JSON.stringify({
        pageId: __ENV.PAGE_ID,
        placementId: __ENV.PLACEMENT_ID
    }), {headers: {Authorization: `Bearer ${__ENV.ACCESS_TOKEN}`, 'Content-Type': 'application/json'}});
    check(r, {'200': r => r.status === 200});
}
