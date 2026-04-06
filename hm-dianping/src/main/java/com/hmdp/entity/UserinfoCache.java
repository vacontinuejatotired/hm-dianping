package com.hmdp.entity;

import com.hmdp.dto.UserDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserinfoCache extends UserDTO {
    private Long id;
    private String nickName;
    private String icon;

}
