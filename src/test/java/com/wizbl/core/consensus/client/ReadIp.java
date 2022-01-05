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

package com.wizbl.core.consensus.client;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ReadIp {

    /**
     * readFile from path.
     */
    public String readFile(String path) {
        String laststr = "";
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String tempString = null;
            while ((tempString = reader.readLine()) != null) {
                laststr += tempString;
            }
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        return laststr;
    }

}
