package com.yangdingshan.stockchose.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;

/**
 * @Author: yangdingshan
 * @Date: 2024/2/27 17:21
 * @Description:
 */
@Slf4j
@Service
public class WxOrderService {

    public void getPhone() {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("pageSize", "500");
        paramMap.put("pageNo", "1");
        paramMap.put("userTel", "首单");
        paramMap.put("status", "ALL");
        /**
         * 西科大：2022121747130000023706
         * 万达：2024041147130015821147
         */
        setWanDaParam(paramMap);
        String body = HttpRequest.post("https://gray.scwmwl.com/api/admin/booking/searchOrder")
                .header("wmwl_session", "pc_1715312820670$wx283f4012384f371b$adcbe31b8526850a58289b7095d940ed")
                .body(JSONUtil.toJsonStr(paramMap))
                .execute().body();
        JSONArray jsonArray = JSONUtil.parseObj(body).getJSONObject("data").getJSONArray("data");
        for (Object o : jsonArray) {
            JSONObject jsonObject = (JSONObject) o;
            System.out.println(jsonObject.getStr("tel").replaceAll("-", ""));
        }
    }

    private void setXiKeDaParam(HashMap<String, Object> paramMap) {
        paramMap.put("startDate", "2024-02-27");
        paramMap.put("endDate", "2024-04-26");
        paramMap.put("shopCode", "2022121747130000023706");
    }

    private void setWanDaParam(HashMap<String, Object> paramMap) {
        paramMap.put("startDate", "2024-04-26");
        paramMap.put("endDate", "2024-04-28");
        paramMap.put("shopCode", "2024041147130015821147");
    }
}
