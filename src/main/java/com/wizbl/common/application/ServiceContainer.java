/*
 * java-brte2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-brte2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wizbl.common.application;

import com.wizbl.core.config.args.Args;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

/**
 * Application에 포함된 Service를 관리하는 클래스<br/>
 * service와 관련된 설정값 세팅, start, stop을 제어함.
 */
@Slf4j
public class ServiceContainer {
  private ArrayList<Service> services;

  public ServiceContainer() {
    this.services = new ArrayList<>();
  }

  public void add(Service service) {
    this.services.add(service);
  }


  public void init() {
    for (Service service : this.services) {
      logger.debug("Initing " + service.getClass().getSimpleName());
      service.init();
    }
  }

  public void init(Args args) {
    for (Service service : this.services) {
      logger.debug("Initing " + service.getClass().getSimpleName());
      service.init(args);
    }
  }

  public void start() {
    logger.debug("Starting services");
    for (Service service : this.services) {
      logger.debug("Starting " + service.getClass().getSimpleName());
      service.start();
    }
  }

  public void stop() {
    for (Service service : this.services) {
      logger.debug("Stopping " + service.getClass().getSimpleName());
      service.stop();
    }
  }
}
