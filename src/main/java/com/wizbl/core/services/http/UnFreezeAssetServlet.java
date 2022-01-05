package com.wizbl.core.services.http;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.core.Wallet;


@Component
@Slf4j
public class UnFreezeAssetServlet extends HttpServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
/* Remark jc.kang 191014
    try {
      String contract = request.getReader().lines()
          .collect(Collectors.joining(System.lineSeparator()));
      UnfreezeAssetContract.Builder build = UnfreezeAssetContract.newBuilder();
      JsonFormat.merge(contract, build);
      Transaction tx = wallet
          .createTransactionCapsule(build.build(), ContractType.UnfreezeAssetContract)
          .getInstance();
      response.getWriter().println(Util.printTransaction(tx));
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }

*/
  }
}
