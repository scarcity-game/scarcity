package com.pessimisticit.scarcity.resourcemodel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

class ResourceControllerTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;

    @Test
    void getOneWithEtagAnd304() throws Exception {
        String game = create(ResourceType.GAME, null, state("name", "g"));
        String area = create(ResourceType.AREA, game, state("w", 10));
        mvc.perform(get("/resources/" + game))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.id").value(game))
                .andExpect(jsonPath("$.type").value("game"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.children[0]").value(area))
                .andExpect(jsonPath("$.state.name").value("g"))
                .andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/resources/" + game).header(HttpHeaders.IF_NONE_MATCH, "\"2\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(content().string(""));
        mvc.perform(get("/resources/" + game).header(HttpHeaders.IF_NONE_MATCH, "\"1\""))
                .andExpect(status().isOk());
    }

    @Test
    void notFoundAndMalformedHaveErrorBody() throws Exception {
        mvc.perform(get("/resources/" + ResourceIds.newId(ResourceType.ENTITY)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/resources/not-an-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/resources/area:nope/children"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/resources/" + ResourceIds.newId(ResourceType.AREA) + "/children"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void listRequiresTypeAndFiltersAndPages() throws Exception {
        String tag = unique();
        for (int i = 0; i < 3; i++) {
            create(ResourceType.GAME, null, state("tag", tag).put("n", i));
        }
        create(ResourceType.GAME, null, state("tag", "other"));
        mvc.perform(get("/resources")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("type is required"));
        mvc.perform(get("/resources").param("type", "zone")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "game").param("tag", tag).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].state.n").value(0));
        mvc.perform(get("/resources").param("type", "game").param("tag", tag).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].state.n").value(2));
        mvc.perform(get("/resources").param("type", "game").param("tag", tag).param("n", "1"))
                .andExpect(jsonPath("$.total").value(1));
        // defaults and clamping
        mvc.perform(get("/resources").param("type", "game").param("tag", tag))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/resources").param("type", "game").param("tag", tag).param("size", "100000"))
                .andExpect(jsonPath("$.size").value(200));
        mvc.perform(get("/resources").param("type", "game").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "game").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "game").param("page", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "game").param("bad key!", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void listWithMaxFilters() throws Exception {
        // Test with exactly 16 filter parameters - should succeed
        StringBuilder url = new StringBuilder("/resources?type=game");
        for (int i = 1; i <= 16; i++) {
            url.append("&k").append(i).append("=v").append(i);
        }
        mvc.perform(get(url.toString()))
                .andExpect(status().isOk());

        // Test with 17 filter parameters - should fail with 400
        url = new StringBuilder("/resources?type=game");
        for (int i = 1; i <= 17; i++) {
            url.append("&k").append(i).append("=v").append(i);
        }
        mvc.perform(get(url.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("at most 16 filter parameters"));
    }

    @Test
    void childrenWithOptionalTypeAndPaging() throws Exception {
        String game = create(ResourceType.GAME, null, state("n", 1));
        String area = create(ResourceType.AREA, game, state("n", 1));
        String e1 = create(ResourceType.ENTITY, area, state("n", 1));
        String e2 = create(ResourceType.ENTITY, area, state("n", 2));
        mvc.perform(get("/resources/" + area + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(e1))
                .andExpect(jsonPath("$.items[1].id").value(e2));
        mvc.perform(get("/resources/" + area + "/children").param("type", "entity").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(e2))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/resources/" + area + "/children").param("type", "item"))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
