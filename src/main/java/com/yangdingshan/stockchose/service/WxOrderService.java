package com.yangdingshan.stockchose.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.HashMap;

/**
 * @Author: yangdingshan
 * @Date: 2024/2/27 17:21
 * @Description:
 */
public class WxOrderService {

    public void getPhones() {


    }

    public static void main(String[] args) {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("pageSize", "500");
        paramMap.put("pageNo", "1");
        paramMap.put("userTel", "首单");
        paramMap.put("startDate", "2024-02-27");
        paramMap.put("endDate", "2024-04-26");
        paramMap.put("status", "ALL");
        /**
         * 西科大：2022121747130000023706
         * 万达：2024041147130015821147
         */
        paramMap.put("shopCode", "2024041147130015821147");
        String body = HttpRequest.post("https://gray.scwmwl.com/api/admin/booking/searchOrder")
                .header("wmwl_session", "pc_1713268083843$wx283f4012384f371b$b35a39f0cf54a4015be8728b35fae7e6")
                .body(JSONUtil.toJsonStr(paramMap))
                .execute().body();
        JSONArray jsonArray = JSONUtil.parseObj(body).getJSONObject("data").getJSONArray("data");
        for (Object o : jsonArray) {
            JSONObject jsonObject = (JSONObject) o;
            System.out.println(jsonObject.getStr("tel").replaceAll("-", ""));
        }
    }
}
