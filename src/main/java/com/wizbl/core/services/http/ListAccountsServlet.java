package com.wizbl.core.services.http;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.db.AccountStore;
import com.wizbl.core.db.Manager;
import com.wizbl.protos.Protocol;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class ListAccountsServlet extends HttpServlet {

    @Autowired
    private Manager dbManager;

    private String convertOutput(Protocol.Account account) {
        // convert asset id
        if (account.getAssetIssuedID().isEmpty()) {
            return JsonFormat.printToString(account);
        } else {
            JSONObject accountJson = JSONObject.parseObject(JsonFormat.printToString(account));
            String assetId = accountJson.get("asset_issued_ID").toString();
            accountJson.put(
                    "asset_issued_ID", ByteString.copyFrom(ByteArray.fromHexString(assetId)).toStringUtf8());
            return accountJson.toJSONString();
        }

    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        try {
            AccountStore accountStore = dbManager.getAccountStore();
            Iterator<Map.Entry<byte[], AccountCapsule>> it = accountStore.iterator();
            List<String> accountList = new ArrayList<>();
            JSONArray accountArr = new JSONArray();
            int i=0;
            while (it.hasNext()) {
                if(i == 10){
                    break;
                }

                Map.Entry<byte[], AccountCapsule> accountData = it.next();
                Protocol.Account account = accountData.getValue().getInstance();
                accountArr.add(convertOutput(account));
                i++;
            }

            if(accountArr.size() != 0 || accountArr == null){
                response.getWriter().println(accountArr);
            }
        } catch (Exception e) {
            logger.debug("Exception: {}", e.getMessage());
            try {
                response.getWriter().println(Util.printErrorMsg(e));
            } catch (IOException ioe) {
                logger.debug("IOException: {}", ioe.getMessage());
            }
        }


    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        doGet(request, response);
    }
}
